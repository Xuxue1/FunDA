package com.bayakala

/**
  * FunDA core types, global imports and fs2 stream method injection
  */
package object funda {
  import fs2._
  import slick.dbio._

  implicit val fda_strategy = Strategy.fromFixedDaemonPool(4)
  implicit val fda_scheduler = Scheduler.fromFixedDaemonPool(4)

  /** 数据处理管道
    * a stream of data or action rows
    * @tparam ROW   type of row
    */
  type FDAPipeLine[ROW] = Stream[Task, ROW]

  /** 数据作业节点
    * a work node appended to stream to perform user action
    * @tparam ROW   type of row
    */
  type FDAWorkNode[ROW] = Pipe[Task, ROW, ROW]

  /** 数据管道开关阀门，从此处获得管道内数据
    * a handle to get rows from upstream
    * @tparam ROW  type of row
    */
  type FDAValve[ROW] = Handle[Task, ROW]

  /** 管道连接器
    * gate to send rows downstream
    * @tparam ROW  type of row
    */
  type FDAPipeJoint[ROW] = Pull[Task, ROW, Unit]

  /** 作业类型
    * user define function to be performed at a FDAWorkNode
    * given a row from upstream, return Option[List[ROW]] as follows:
    *    fda_skip  -> Some(Nil)           : skip sending the current row
    *    fda_next  -> Some(List(r1,r2...)): send r1,r2... downstream
    *    fda_break -> None                : halt stream, end of process
    * @tparam ROW   type of row
    */
  type FDAUserTask[ROW] = (ROW) => (Option[List[ROW]])

  /** 合计作业类型
    * user define function with aggregation effect to be performed at a FDAWorkNode
    * given current aggregation value and row from upstream,
    * return updated aggregation value and Option[List[ROW]] as follows:
    *    fda_skip  -> Some(Nil)           : skip sending the current row
    *    fda_next  -> Some(List(r1,r2...)): send r1,r2... downstream
    *    fda_break -> None                : halt stream, end of process
    * @tparam AGGR  type of aggregation
    * @tparam ROW   type of row
    */
  type FDAAggrTask[AGGR,ROW] = (AGGR,ROW) => (AGGR,Option[List[ROW]])

  /** 并行作业类型
    * stream of streams type for parallel running user action
    * use stream.toPar to convert from FDAUserTask
    */
  type FDAParTask = Stream[Task,Stream[Task,Option[List[FDAROW]]]]


  /** 数据行类型
    * topmost generic row type
    */
  trait FDAROW

  /**
    * a EOS object used to signify end of stream
    */
  case object FDANullRow extends FDAROW

  /**
    * capture exception in a row
    * @param e
    */
  case class FDAErrorRow(e: Exception) extends FDAROW

  /**
    * designed to emit FDANullRow or FDAErrorRow
    * @param row   row to emit
    * @return      new stream
    */
  def fda_appendRow(row: FDAROW): FDAPipeLine[FDAROW] = Stream(row)

  /**
    * runnable action type
    */
  type FDAAction = DBIO[Int]

  /**
    * action row type. can have futher distinct child type as fullows:
    * @example {{{
    * scala> class MyActionRow(action: FDAAction) extends FDAActionRow(action)
    * }}}
    * @param action   runnable action
    */
  case class FDAActionRow(action: FDAAction) extends FDAROW

  /**
    * methods injected to fs2Stream
    */
  implicit class toFDAOps(fs2Stream: FDAPipeLine[FDAROW]) {
    /**
      * append a user task t to stream
      *
      * @param t user defined function
      * @return new stream
      */
    def appendTask(t: FDAUserTask[FDAROW]) = fs2Stream.through(FDATask.fda_execUserTask(t))

    /**
      * append a user defined aggregation task t
      *
      * @param aggr     initial value of aggregation
      * @param t        user defined task
      * @tparam AGGR    type of aggr
      * @return         new stream
      */
    def aggregateTask[AGGR](aggr: AGGR, t: FDAAggrTask[AGGR,FDAROW]) = fs2Stream.through(FDATask.fda_aggregate(aggr,t))

    /**
      * replace stream[Task,ROW].run.unsafeRun
      */
    def startRun = fs2Stream.run.unsafeRun

    /**
      * replace stream[Task,ROW].run.unsafeRunAsyncFuture
      * returns immediately
      *
      * @return Future
      */
    def startFuture = fs2Stream.run.unsafeRunAsyncFuture


    /**
      * turn user task into type for parallel computation
      *
      * @param st user defined task
      * @return stream of streams
      */
    def toPar(st: FDAUserTask[FDAROW]): Stream[Task, Stream[Task, Option[List[FDAROW]]]] =
      fs2Stream.map { row =>
        Stream.eval(Task {
          st(row)
        })
      }
  }

  /** methods to run an user defined function on FDAPipeLine*/
  object FDATask { //作业节点工作方法
    /**
      * returns state of next worknode. using fs2 Handle of Pull object,
      * take the next element and apply function task and determine new state of stream
      * @param task   user defined function: ROW => Option[List[ROW]]
      *               returns an Option[List[ROW]]] value signifying movement downstream
      *               as follows:
      *                  Some(Nil)           : skip sending the current row
      *                  Some(List(r1,r2...)): send r1,r2... downstream
      *                  None                : halt stream, end of process
      * @tparam ROW   row type: FDAROW or FDAActionROW
      * @return       new state of stream
      */
     def fda_execUserTask[ROW](task: FDAUserTask[ROW]): FDAWorkNode[ROW] = {
      def go: FDAValve[ROW] => FDAPipeJoint[ROW] = h => {
        h.receive1Option {
          case Some((r, h)) => task(r) match {
            case Some(lx) => lx match {
              case Nil => go(h)
              case _ => Pull.output(Chunk.seq(lx)) >> go(h)
            }
            case None => task(FDANullRow.asInstanceOf[ROW]) match {
              case Some(lx) => lx match {
                case Nil => Pull.done
                case _ => Pull.output(Chunk.seq(lx)) >> Pull.done
              }
              case _ => Pull.done
            }
          }
          case None => task(FDANullRow.asInstanceOf[ROW]) match {
            case Some(lx) => lx match {
              case Nil => Pull.done
              case _ => Pull.output(Chunk.seq(lx)) >> Pull.done
            }
            case _ => Pull.done
          }
        }
      }
      in => in.pull(go)
    }
    /**
      * returns state of next worknode and some aggregation defined inside user function.
      * execute user defined function with internal aggregation mechanism by means of
      * functional state transition style of passing in state and return new state.
      * take in current aggregation and next row, apply user function on both
      * and determine new state of stream
      * @param aggr    user selected type of aggregation such as Int, (Int,Int) ...
      * @param task    user defined function: (AGGR,ROW) => (AGGR,Option[List[ROW]])
      *                take in current aggregation and row,
      *                and return new aggregation and Option[List[ROW]] with meaning of:
      *                  Some(Nil)           : skip sending the current row
      *                  Some(List(r1,r2...)): send r1,r2... downstream
      *                  None                : halt stream, end of process
      * @tparam AGGR   type of aggr
      * @tparam ROW    type of row
      * @return        new state of stream
      */
    def fda_aggregate[AGGR,ROW](aggr: AGGR, task: FDAAggrTask[AGGR,ROW]): FDAWorkNode[ROW] = {
      def go(acc: AGGR): FDAValve[ROW] => FDAPipeJoint[ROW] = h => {
        h.receive1Option {
          case Some((r, h)) => task(acc,r) match {
            case (a,Some(lx)) => lx match {
              case Nil => go(a)(h)
              case _ => Pull.output(Chunk.seq(lx)) >> go(a)(h)
            }
            case (a,None) => task(a,FDANullRow.asInstanceOf[ROW]) match {
              case (a,Some(lx)) => lx match {
                case Nil => Pull.done
                case _ => Pull.output(Chunk.seq(lx)) >> Pull.done
              }
              case _ => Pull.done
            }
          }
          case None => task(acc,FDANullRow.asInstanceOf[ROW]) match {
            case (a,Some(lx)) => lx match {
              case Nil => Pull.done
              case _ => Pull.output(Chunk.seq(lx)) >> Pull.done
            }
            case _ => Pull.done
          }
        }
      }
      in => in.pull(go(aggr))
    }


  }


}


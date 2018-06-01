package minescraper

import java.lang.System.nanoTime
import scala.concurrent.{ Future, Await }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.{ Try, Success, Failure }
import slick.jdbc.meta.MTable
import slick.jdbc.SQLiteProfile.api._
import net.ruippeixotog.scalascraper.browser.JsoupBrowser

object Main {
  val appStart = nanoTime()

  def createTables(db: Database): DBIO[Unit] = {
    val tables = List(Tables.forums, Tables.threads, Tables.users, Tables.posts, Tables.likes)

    MTable.getTables flatMap { existing =>
      val names = existing.map(_.name.name)

      DBIO.seq((tables filter { table =>
        !names.contains(table.baseTableRow.tableName)
      } map { _.schema.create }): _*)
    }
  }

  def downloadForums[T](values: Seq[Int], dl: Downloader): Iterator[DBIO[Option[Int]]] = {
    val taggedValues = values.grouped(10)
    for(ids <- taggedValues)
      yield {
        val start = nanoTime()
        val stats = DBIO.sequence(ids map { id =>
          Tables.forums.filter(_.id === id).result.headOption flatMap {
            case Some(_) => DBIO.successful(Nil)
            case None =>
              DBIO.from(dl.forum(id)) map {
                case None => Nil
                case Some(forum) => List(forum)
              }
            }
          }) flatMap { f => Tables.forums ++= f.flatten } map { inserts =>
            println(f"[C] Forums :: ${ids.head}%6d - ${ids.last}%6d: ${inserts getOrElse -1} NEW IN.")
            inserts
          }
        val end = nanoTime()
        println(f"[P] Forums :: ${ids.head}%6d - ${ids.last}%6d in ${(end-start)/1000D/1000D}%8.2fms")
        stats
      }
  }

  def downloadThreads[T](values: Seq[Int], dl: Downloader): Iterator[DBIO[Option[Int]]] = {
    val taggedValues = values.grouped(500)
    for(ids <- taggedValues)
      yield {
        val start = nanoTime()

        val stats = Tables.threads.filter(_.id inSet ids).map(_.id).result flatMap { existing =>
          val nonExisting = ids filterNot (existing.contains)
          if(nonExisting.isEmpty)  {
            DBIO.successful(None)
          } else {
            val dlStart = nanoTime()
            val newThreads = for(threads <- Future.traverse(nonExisting)(dl.thread)) yield {
              val dlEnd = nanoTime()
              val flattened = threads.flatten
              println(f"[D] Threads :: ${ids.head}%6d - ${ids.last}%6d in ${(dlEnd-dlStart)/1000D/1000D}%8.2fms - ${flattened.length} out of ${threads.length}")

              (Tables.threads ++= threads.flatten) map { inserts =>
                println(f"[C] Threads :: ${ids.head}%6d - ${ids.last}%6d: ${inserts getOrElse -1} NEW IN (out of ${nonExisting.length}).")
                inserts
              }
            }
            DBIO.from(newThreads).flatten
          }
        }
        val end = nanoTime()
        println(f"[P] Threads :: ${ids.head}%6d - ${ids.last}%6d in ${(end-start)/1000D/1000D}%8.2fms")
        stats
      }
  }

  def downloadUsers[T](values: Seq[Int], dl: Downloader): Seq[DBIO[Option[Int]]] = {
    val taggedValues = values.grouped(500).toList
    for(ids <- taggedValues)
      yield {
        val start = nanoTime()

        val stats = Tables.users.filter(_.id inSet ids).map(_.id).result flatMap { existing =>
          val nonExisting = ids filterNot (existing.contains)
          if(nonExisting.isEmpty)  {
            DBIO.successful(None)
          } else {
            val dlStart = nanoTime()
            val newUsers = for(users <- Future.traverse(nonExisting)(dl.user)) yield {
              val dlEnd = nanoTime()
              val flattened = users.flatten
              println(f"[D] Users :: ${ids.head}%6d - ${ids.last}%6d in ${(dlEnd-dlStart)/1000D/1000D}%8.2fms - ${flattened.length} out of ${users.length} Starting at ${(dlStart-appStart)/1000D/1000D/1000D}%8.3fs")

              (Tables.users ++= users.flatten) map { inserts =>
                println(f"[C] Users :: ${ids.head}%6d - ${ids.last}%6d: ${inserts getOrElse -1} NEW IN (out of ${nonExisting.length}).")
                inserts
              }
            }
            DBIO.from(newUsers).flatten
          }
        }
        val end = nanoTime()
        println(f"[P] Users :: ${ids.head}%6d - ${ids.last}%6d in ${(end-start)/1000D/1000D}%8.2fms")
        stats
      }
  }

  def downloadPosts[T](dl: Downloader)(implicit session: Session): DBIO[Seq[(Int, DBIO[(Int, Int)])]] = {
    val unfinished = Tables.threads.joinLeft(Tables.posts).on(_.id === _.threadId).filter {
      _._1.exists === true // Nur existente Threads
    }.groupBy {
      _._1.id
    }.map { case (id, row) => // Gruppieren nach Thread
      (id, row.length, row.map(_._1.postCount).max, row.map(_._2.map(_.id)).countDefined)
    }.filter { g =>
       g._3 =!= 0 && // Keine leeren Threads.
      (g._2 =!= g._3 || (g._4 === 0 && g._3 === 1))  // Posts
    }.map { r =>
      (r._1, r._3 getOrElse 1, r._4)
    }.sortBy {
      _._2.asc
    }.result

    unfinished flatMap { threads =>
      println(s"${threads.length} Threads to go!\n")
      val actions = threads.map { case (threadId, postCount, _) =>
        val pageGroups = (1 to (
          if(postCount % 10 == 0)
            postCount / 10
          else
            (postCount / 10) + 1)).grouped(100)

        val insertActions = for(pageGroup <- pageGroups) yield DBIO.from {
          val downloads = pageGroup.map { dl.posts(threadId, _) }
          val bundled = Future.sequence(downloads) map { _.flatten }
          val postAndLikes = bundled map { posts =>
            posts.foldLeft((List[Tables.PostT](), List[Tables.LikeT]())) { case ((accPosts, accLikes), (post, likes)) =>
              (post :: accPosts, likes.toList ::: accLikes)
            }
          }
          postAndLikes map { case (posts, likes) =>
            (Tables.posts ++= posts) zip ((Tables.likes ++= likes).asTry map {
              case Success(s) => s
              case Failure(_) => None
            }) map { case (postInsert, likeInsert) =>
              (postInsert getOrElse 0, likeInsert getOrElse 0)
            }
          }
        }.flatten

        (threadId,
          DBIO.sequence(insertActions) map { r =>
          println(s"Finished the following thread: ${threadId}.")
          r.foldLeft((0, 0)) { case ((accP, accL), (posts, likes)) =>
            (accP + posts, accL + likes)
          }
        })
      }
      val preExisting = Tables.posts.filter(_.threadId inSet threads.map(_._1).toSet)
      (preExisting.map(_.id).result flatMap { posts =>
        Tables.likes.filter(_.likedPost inSet posts.toSet).delete
      } andThen preExisting.delete) map { _ => actions }
    }
  }

  def main(args: Array[String]): Unit = {
    import org.jsoup.Connection
    val dl = new Downloader(new JsoupBrowser {
      override def defaultRequestSettings(conn: Connection): Connection =
        super.defaultRequestSettings(conn)//.timeout(150000)
    })

    val db = Database.forURL(
      "jdbc:sqlite:MC.sqlite3",
      driver = "org.sqlite.JDBC"
    )
    implicit val session: Session = db.createSession()

    try {
      val creationStatement = createTables(db)

      /*val forums = (1 to 1)//300)
      val threads = (1 to 135000)
      val existingUsers = Await.result(db.run(Tables.users.map(_.id).result), Duration.Inf).toSet
      val users: Set[Int] = (1 to 155000).toSet diff existingUsers

      val forumStatement = DBIO.seq(downloadForums(forums, dl).toList: _*)
      val firstStep = creationStatement andThen forumStatement
      Await.result(db.run(firstStep), Duration.Inf)*/

      Await.result(db.run(
        (Tables.forums.filter(_.exists === false).delete) andThen
        (Tables.threads.filter(_.exists === false).delete) andThen
        (Tables.users.filter(_.exists === false).delete)
      ), Duration.Inf)

      println(Await.result(db.run(Tables.forums.length.result), Duration.Inf))
      println(Await.result(db.run(Tables.threads.length.result), Duration.Inf))
      println(Await.result(db.run(Tables.users.length.result), Duration.Inf))

/*
      val r = Await.result(db.run(Tables.threads.joinLeft(Tables.posts).on(_.id === _.threadId).filter {
        _._1.exists === true // Nur existente Threads
      }.groupBy { _._1.id }.map { case (id, row) => // Gruppieren nach Thread
        (id, row.length, row.map(_._1.postCount).max, row.map(_._2.map(_.id)).countDefined)
      }.filter { g =>
         g._3 =!= 0 && // Keine leeren Threads.
        (g._2 =!= g._3 || (g._4 === 0 && g._3 === 1))  // Posts
      }.map { r => (r._1, r._3 getOrElse 1, r._4) }.sortBy { _._2.asc }.result), Duration.Inf)
      println(r)
      ???
*/
      // val threadResults = for(tGroup <- threads.grouped(10000)) yield {
      //   val threadStatements = downloadThreads(tGroup, dl)
      //
      //   Await.result(Future.sequence(threadStatements.map(db.run(_))) transform {
      //     case Failure(error) =>
      //       Success(Some((tGroup.head, tGroup.last, error)))
      //     case Success(_)     =>
      //       Success(None)
      //   }, Duration.Inf)
      // }
      // for((start, end, error) <- threadResults.toList.map(_.toList).flatten) {
      //   println(s"There was an error from $start to $end: $error")
      //   error.printStackTrace()
      // }
      //
      // val userResults = for(uGroup <- users.toSeq.sorted.grouped(1)) yield {
      //   val userStatements = downloadUsers(uGroup, dl)
      //
      //   Await.result(Future.sequence(userStatements.map(db.run(_))) transform {
      //     case Failure(error) =>
      //       Success(Some((uGroup.head, uGroup.last, error)))
      //     case Success(_)     =>
      //       Success(None)
      //   }, Duration.Inf)
      // }
      // for((start, end, error) <- userResults.toList.map(_.toList).flatten) {
      //   println(s"There was an error from $start to $end: $error")
      //   error.printStackTrace()
      // }

      // val doubles =
      //   Await.result(
      //     db.run(Tables.threads.filter(t => t.exists === true && t.postCount =!= 0 && t.id > 54000).result
      //       .flatMap(threads => {
      //         println(threads.head)
      //         DBIO.sequence(
      //           threads.map(th => (th._1, Await.result(dl.posts(th._1, th._5), Duration.Inf).lastOption.map(_._1._5).getOrElse(0)))
      //                  .filter(t => t._1 != t._2)
      //                  .map(t => Tables.threads.filter(_.id === t._1).map(_.postCount).update(t._2))
      //         )
      //       })
      //     ), Duration.Inf)
/*
      Await.result({
        db.run(downloadPosts(dl)) map { iter =>
          for((threadId, action) <- iter) {
            try Await.result(db.run(action), Duration.Inf)
            catch {
              case _: org.sqlite.SQLiteException =>
                Await.result(db.run(Tables.threads.filter(_.id === threadId).delete), Duration.Inf)
            }
          }
        }
      }, Duration.Inf)*/

    } finally session.close()
  }
}

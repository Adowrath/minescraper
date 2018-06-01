
package minescraper
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

import net.ruippeixotog.scalascraper.browser.Browser
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL.Parse._
import net.ruippeixotog.scalascraper.model._

class Downloader(browser: Browser) {
  private val forumReg = """forumdisplay\.php\?(\d+).*""".r
  private def forumDisplay(id: Int): String = s"https://minecraft.de/forumdisplay.php?$id"
  def forum(forumId: Int): Future[Option[Tables.ForumT]] = Future {
    val doc = browser get forumDisplay(forumId)
    doc >?> element(".standard_error") match {
      case Some(_) => Some((forumId, false, None, "")) // Kein Forum
      case None =>
        (doc >> elementList(".breadcrumb .navbit")).reverse match {
          case our :: _      :: Nil =>
            Some((forumId, true, None, our.text))
          case our :: parent :: _   =>
            Some((forumId, true, parent >> attr("href")("a") match {
              case forumReg(parId) => Some(parId.toInt)
            }, our.text))
          case _ => None
        }
    }
  }

  private val pageCountReg = """Ergebnis 1 bis \d+ von (\d+)""".r
  private def showThread(id: Int): String = s"https://minecraft.de/showthread.php?$id"
  def thread(threadId: Int): Future[Option[Tables.ThreadT]] = Future {
    val doc = browser get showThread(threadId)
    doc >?> element(".standard_error") match {
      case Some(_) => Some((threadId, false, 0, "", 0)) // Kein Thread
      case None =>
        (doc >> elementList(".breadcrumb .navbit")).reverse match {
          case our :: forum      :: _ =>
            Some((
              threadId, true,
              forum >> attr("href")("a") match {
                case forumReg(parId) => parId.toInt
              },
              our.text,
              doc >> text(".pagination_top .postpagestats") match {
                case pageCountReg(pcStr) => pcStr.toInt
              }
            ))
          case _ => None
        }
    }
  }

  private def member(id: Int): String = s"https://minecraft.de/member.php?$id"
  def user(userId: Int): Future[Option[Tables.UserT]] = Future {
    val doc = browser get member(userId)
    doc >?> element(".standard_error") match {
      case Some(_) => Some((userId, false, "")) // Kein User
      case None =>
        doc >?> text(".member_username") map { (userId, true, _) }
    }
  }

  private val posterReg = """member\.php\?(\d+).*""".r
  private val postIdReg = """^post(\d+)$""".r
  private def threadOnPage(id: Int, page: Int): String = s"https://minecraft.de/showthread.php?$id/page$page"
  def posts(threadId: Int, page: Int): Future[Seq[(Tables.PostT, Seq[Tables.LikeT])]] = Future {
    val doc = browser get threadOnPage(threadId, page)
    val postList = doc >> element(".postlist ol.posts")
    val posts = postList >> elementList("li[id^=post_]")

    if(true) {
      println(s"Thread $threadId on page $page has ${posts.length} posts: ${posts.map(post => (post >> text(".posthead .postcounter")).tail.toInt)}.")
    }

    for(post <- posts) yield {
      val postNumber = post >> attr("name")(".posthead .postcounter") match {
        case postIdReg(pId) => pId.toInt
      }
      val postInThread = (post >> text(".posthead .postcounter")).tail.toInt
      val postedDate = post >> text(".posthead .date")

      val poster = post >?> attr("href")(".postdetails .userinfo .username") map {
        case posterReg(posterId) => posterId.toInt
      }

      val postContent = (post >> element(".postdetails .postrow .content blockquote.postcontent")).innerHtml

      val likes = (postList >> elementList(s"#dbtech_thanks_entries_$postNumber a") foldLeft List[Tables.LikeT]()) { (acc, like) =>
        like.attr("href") match {
          case posterReg(liker) => (postNumber, liker.toInt, like.attr("title")) :: acc
          case _ => acc
        }
      }

      ((postNumber, threadId, poster, postedDate, postInThread, postContent), likes)
    }
  }
}

package minescraper

import slick.jdbc.SQLiteProfile.api._

object Tables {
  type ForumT = (Int, Boolean, Option[Int], String)
  class Forum(tag: Tag) extends Table[ForumT](tag, "mc_forum") {
    def id = column[Int]("forum_id", O.PrimaryKey)
    def exists = column[Boolean]("forum_exists")
    def parentForum = column[Option[Int]]("parent_forum")
    def name = column[String]("forum_name")
    def * = (id, exists, parentForum, name)

    def forum = foreignKey("PAR_FORUM_FK", parentForum, forums)(_.id.?, onUpdate=ForeignKeyAction.Restrict, onDelete=ForeignKeyAction.Cascade)
  }
  val forums = TableQuery[Forum]


  type ThreadT = (Int, Boolean, Int, String, Int)
  class Thread(tag: Tag) extends Table[ThreadT](tag, "mc_thread") {
      def id = column[Int]("thread_id", O.PrimaryKey)
      def forumId = column[Int]("_forum_id")
      def exists = column[Boolean]("forum_exists")
      def name = column[String]("thread_name")
      def postCount = column[Int]("post_count")
      def * = (id, exists, forumId, name, postCount)

      def forum = foreignKey("FORUM_FK", forumId, forums)(_.id, onUpdate=ForeignKeyAction.Restrict, onDelete=ForeignKeyAction.Cascade)
  }
  val threads = TableQuery[Thread]


  type UserT = (Int, Boolean, String)
  class User(tag: Tag) extends Table[UserT](tag, "mc_user") {
      def id = column[Int]("user_id", O.PrimaryKey)
      def exists = column[Boolean]("user_exists")
      def userName = column[String]("user_name")
      def * = (id, exists, userName)
  }
  val users = TableQuery[User]


  type PostT = (Int, Int, Option[Int], String, Int, String)
  class Post(tag: Tag) extends Table[PostT](tag, "mc_post") {
      def id = column[Int]("post_id", O.PrimaryKey)
      def threadId = column[Int]("_thread_id")
      def posterId = column[Option[Int]]("_poster_id")
      def date = column[String]("posted_date")
      def postNum = column[Int]("post_num")
      def content = column[String]("post_content")
      def * = (id, threadId, posterId, date, postNum, content)

      def thread = foreignKey("THREAD_FK", threadId, threads)(_.id, onUpdate=ForeignKeyAction.Restrict, onDelete=ForeignKeyAction.Cascade)
      def poster = foreignKey("POSTER_FK", posterId, users)(_.id.?, onUpdate=ForeignKeyAction.Restrict, onDelete=ForeignKeyAction.Cascade)
  }
  val posts = TableQuery[Post]


  type LikeT = (Int, Int, String)
  class Like(tag: Tag) extends Table[LikeT](tag, "mc_like") {
      def likedPost = column[Int]("_liked_post")
      def likedBy = column[Int]("_liked_by")
      def date = column[String]("liked_on")
      def * = (likedPost, likedBy, date)

      def pk = primaryKey("LIKE_PK", (likedPost, likedBy))
      def post = foreignKey("L_POST_FK", likedPost, posts)(_.id, onUpdate=ForeignKeyAction.Restrict, onDelete=ForeignKeyAction.Cascade)
      def liker = foreignKey("L_LIKER_FK", likedBy, users)(_.id, onUpdate=ForeignKeyAction.Restrict, onDelete=ForeignKeyAction.Cascade)
  }
  val likes = TableQuery[Like]


}

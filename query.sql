select
  s28.thread_id,
  s28.post_count
from
  mc_thread as s28
  left outer join
    mc_post as s29
	on s28.thread_id = s29._thread_id
where
  s28.forum_exists = 1
  and s28.thread_id = 50353
group by s28.thread_id
having
  not s28.post_count = 0
  and (
    (not count(1) = s28.post_count)
     or (
	    count(s29.post_id) = 0
	    and s28.post_count = 1))
order by
  s28.post_count

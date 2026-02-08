# cs370db

commit f1c5c4e497c3f5501229b71db005a9984077687d
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Sun Feb 8 09:18:05 2026 -0500

    broke up to helper functions, got altitude working, date/time converted out of exif to java sql compat, set flags. TODO: hashing algo or some serial to for fast lookups

 Metadata.java |   2 ++
 README.md     |   3 +-
 db.java       | 109 ++++++++++++++++++++++++++++++++++------------------------
 exif.java     |  18 +++++++++-
 4 files changed, 84 insertions(+), 48 deletions(-)

commit ea728d2fdadde7b762f1a694bf09ae3b8158d560
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Sat Feb 7 22:11:01 2026 -0500

    low level byte image parser completed and somewhat implemented. need to split up helper function for passing to db. but it's functional

 .DS_Store     | Bin 0 -> 6148 bytes
 Metadata.java |   9 ++++
 README.md     |   6 +--
 db.class      | Bin 1900 -> 0 bytes
 db.java       |  36 ++++++++++++---
 exif.java     | 145 ++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
 6 files changed, 187 insertions(+), 9 deletions(-)

commit 470f73639ea6932270bedc30bb816ad56f110734
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Fri Feb 6 14:43:00 2026 -0500

    ..

 db.class | Bin 1869 -> 1900 bytes
 db.java  |  31 +++++++++++--------------------
 2 files changed, 11 insertions(+), 20 deletions(-)

commit 4b09a0e47a5211f8b292fa85c6f54ffac2d49264
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Fri Feb 6 14:12:50 2026 -0500

    jdbc statement handling

 README.md             |  12 +++++++++++-
 db.class              | Bin 0 -> 1869 bytes
 db.java               |  44 ++++++++++++++++++++++++++++++++++++++++++++
 postgresql-42.7.8.jar | Bin 0 -> 1116727 bytes
 4 files changed, 55 insertions(+), 1 deletion(-)

commit a0d86fa1dd474b0ba9a0023317f89be7ba524bf7
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Fri Feb 6 13:49:17 2026 -0500

    Initial commit

 README.md | 1 +
 1 file changed, 1 insertion(+)

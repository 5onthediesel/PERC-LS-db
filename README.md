commit 4b82067c61dc6abc98d26496bf5d6dd52b77c8ae
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Sun Feb 8 13:04:40 2026 -0500

    removed cached .ds_store's

 .DS_Store        | Bin 6148 -> 0 bytes
 images/.DS_Store | Bin 6148 -> 0 bytes
 2 files changed, 0 insertions(+), 0 deletions(-)

commit 2c864bf4f5cd7e1992ec36142251f5444aa28fe4
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Sun Feb 8 13:03:06 2026 -0500

    Add .DS_Store to .gitignore

 .gitignore | 1 +
 1 file changed, 1 insertion(+)

commit f7b02d975ee93b8158a77b0b6dfc139ec10b7d94
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Sun Feb 8 13:01:08 2026 -0500

    hashing and duplicate checks. have hash as unique currently but can change to serial or some other identifier.

 ImgHash.java | 30 ++++++++++++++++++++++++++++++
 db.java      | 43 ++++++++++++++++++++++++++++---------------
 2 files changed, 58 insertions(+), 15 deletions(-)

commit 0dedd3b362a255f2274d23dc364b8d5bf473f46e
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Sun Feb 8 10:48:58 2026 -0500

    .heic image parsing supported. new helper added. TODO: hashing. finish by today.

 .DS_Store                       | Bin 6148 -> 6148 bytes
 EXIFParser.java                 |   8 --------
 ImgDet.java                     |  43 ++++++++++++++++++++++++++++++++++++++++
 db.java                         |  35 +++++++++++++++++++++++++++-----
 images/.DS_Store                | Bin 0 -> 6148 bytes
 temp.jpg => images/IMG_3141.jpg | Bin
 images/IMG_3142.jpg             | Bin 0 -> 2116844 bytes
 images/IMG_3143.jpg             | Bin 0 -> 4544190 bytes
 8 files changed, 73 insertions(+), 13 deletions(-)

commit 35050f29264ade593b99ba8d1c992b44beddc9c3
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Sun Feb 8 09:27:27 2026 -0500

    rename class

 EXIFParser$ExifData.class | Bin 379 -> 0 bytes
 EXIFParser.class          | Bin 4304 -> 0 bytes
 Metadata.class            | Bin 411 -> 0 bytes
 db.class                  | Bin 3297 -> 0 bytes
 exif$ExifData.class       | Bin 361 -> 0 bytes
 exif.class                | Bin 4268 -> 0 bytes
 exif.java                 | 161 ----------------------------------------------
 7 files changed, 161 deletions(-)

commit 7e15333e1c5ad3a772762e1ee21eeac40d00d7c0
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Sun Feb 8 09:26:57 2026 -0500

    rename class

 EXIFParser$ExifData.class | Bin 0 -> 379 bytes
 EXIFParser.class          | Bin 0 -> 4304 bytes
 EXIFParser.java           | 161 ++++++++++++++++++++++++++++++++++++++++++++++
 Metadata.class            | Bin 0 -> 411 bytes
 db.class                  | Bin 0 -> 3297 bytes
 db.java                   |   2 +-
 exif$ExifData.class       | Bin 0 -> 361 bytes
 exif.class                | Bin 0 -> 4268 bytes
 temp.jpg                  | Bin 0 -> 1415990 bytes
 9 files changed, 162 insertions(+), 1 deletion(-)

commit 5cd1f2834b3770ed9d51d9cba3fa9c512a91fac1
Author: 5onthediesel <weinshenker.brian@gmail.com>
Date:   Sun Feb 8 09:23:01 2026 -0500

    log stat to readme

 README.md | 60 ++++++++++++++++++++++++++++++++++++++++++++++++++++++------
 1 file changed, 54 insertions(+), 6 deletions(-)

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

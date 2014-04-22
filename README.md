# FileCache

## statement

FileCache is a simple building block for the Scala programming language, managing a directory with cache files. It is (C)opyright 2013&ndash;2014 by Hanns Holger Rutz. All rights reserved. This project is released under the [GNU Lesser General Public License](https://raw.github.com/Sciss/FileCache/master/LICENSE) v2.1+ and comes with absolutely no warranties. To contact the author, send an email to `contact at sciss.de`

## linking

To link to this library, use either of the following artifacts:

    "de.sciss" %% "filecache-mutable" % v
    "de.sciss" %% "filecache-txn"     % v

The current version `v` is `"0.3.2+"`. The `-mutable` variant provides a (thread-safe) mutable cache object, whereas the `-txn` variant uses the [scala-stm](https://github.com/nbronson/scala-stm) software transactional memory.

## building

This project currently builds against Scala 2.11, 2.10, using sbt 0.13.

The project is documented through scaladoc run `sbt doc` to create the API docs.

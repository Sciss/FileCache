package de.sciss.filecache

import concurrent.duration.Duration

/** Maximum capacity specification
  *
  * @param count    maximum number of cache entries (or `-1` for unlimited entries)
  * @param space    maximum volume of cache in bytes (or `-1L` for unlimited space)
  * @param age      maximum age of cache entries in duration since now (or `Duration.Inf` for unlimited age)
  */
final case class Limit(count: Int = -1, space: Long = -1L, age: Duration = Duration.Inf)

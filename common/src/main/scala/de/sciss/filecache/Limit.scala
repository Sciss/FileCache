/*
 *  Limit.scala
 *  (FileCache)
 *
 *  Copyright (c) 2013-2017 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is published under the GNU Lesser General Public License v2.1+
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package de.sciss.filecache

/** Maximum capacity specification
  *
  * @param count    maximum number of cache entries (or `-1` for unlimited entries)
  * @param space    maximum volume of cache in bytes (or `-1L` for unlimited space)
  */
final case class Limit(count: Int = -1, space: Long = -1L)
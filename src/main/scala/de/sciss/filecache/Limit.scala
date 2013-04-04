/*
 *  Limit.scala
 *  (FileCache)
 *
 *  Copyright (c) 2013 Hanns Holger Rutz. All rights reserved.
 *
 *	This software is free software; you can redistribute it and/or
 *	modify it under the terms of the GNU General Public License
 *	as published by the Free Software Foundation; either
 *	version 2, june 1991 of the License, or (at your option) any later version.
 *
 *	This software is distributed in the hope that it will be useful,
 *	but WITHOUT ANY WARRANTY; without even the implied warranty of
 *	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *	General Public License for more details.
 *
 *	You should have received a copy of the GNU General Public
 *	License (gpl.txt) along with this software; if not, write to the Free Software
 *	Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *	For further information, please contact Hanns Holger Rutz at
 *	contact@sciss.de
 */

package de.sciss.filecache

import concurrent.duration.Duration

/** Maximum capacity specification
  *
  * @param count    maximum number of cache entries (or `-1` for unlimited entries)
  * @param space    maximum volume of cache in bytes (or `-1L` for unlimited space)
  * @param age      maximum age of cache entries in duration since now (or `Duration.Inf` for unlimited age)
  */
final case class Limit(count: Int = -1, space: Long = -1L, age: Duration = Duration.Inf)

package com.smjung.photoclean

import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest

/** Exact duplicates only. Prefix fingerprints filter candidates; they never authorize deletion. */
internal object DuplicateFinder {
 data class Result<T>(val groups:List<List<T>>,val errors:Int,val verified:Int,val bytesRead:Long)
 fun <T> find(
  items:List<T>, sizeOf:(T)->Long?, open:(T)->InputStream?, check:()->Unit,
  progress:(String,Int,Int,Int,Int)->Unit
 ):Result<T> {
  val unique=items.distinct()
  val bySize=linkedMapOf<Long,MutableList<T>>()
  var unknown=false
  unique.forEach { item ->
   check()
   val size=sizeOf(item)?.takeIf { it>=0 } ?: -1L
   if(size<0) unknown=true
   bySize.getOrPut(size) { mutableListOf() }.add(item)
  }
  // Unknown sizes must also be compared with known-size singletons.
  val sized=if(unknown) unique else bySize.values.filter { it.size>1 }.flatten()
  progress("크기 선별",unique.size,unique.size,0,0)
  val buffer=ByteArray(65536)
  var errors=0; var bytesRead=0L
  fun digest(item:T,limit:Long,heartbeat:()->Unit):String? {
   check()
   return try {
    val stream=open(item) ?: throw IOException("Cannot open media")
    stream.use {
     val md=MessageDigest.getInstance("SHA-256")
     var remaining=limit
     while(remaining>0) {
      check()
      val n=it.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
      if(n<0) break
      if(n==0) continue
      md.update(buffer,0,n); remaining-=n; bytesRead+=n
      heartbeat()
     }
     md.digest().joinToString("") { b -> (b.toInt() and 255).toString(16).padStart(2,'0') }
    }
   } catch(_:IOException) { errors++; null }
     catch(_:SecurityException) { errors++; null }
  }
  val byPrefix=linkedMapOf<String,MutableList<T>>()
  sized.forEachIndexed { i,item ->
   check()
   digest(item,65536L) {}?.let { byPrefix.getOrPut(it) { mutableListOf() }.add(item) }
   progress("앞부분 비교",i+1,sized.size,0,0)
  }
  val remaining=byPrefix.values.filter { it.size>1 }.flatten()
  val hashes=linkedMapOf<String,MutableList<T>>()
  var duplicates=0; var groupCount=0; var verified=0
  remaining.forEachIndexed { i,item ->
   check()
   digest(item,Long.MAX_VALUE) { progress("정밀 비교",i,remaining.size,duplicates,groupCount) }?.let { hash ->
    verified++
    val group=hashes.getOrPut(hash) { mutableListOf() }
    if(group.isNotEmpty()) { duplicates++; if(group.size==1) groupCount++ }
    group.add(item)
   }
   progress("정밀 비교",i+1,remaining.size,duplicates,groupCount)
  }
  check()
  return Result(hashes.values.filter { it.size>1 },errors,verified,bytesRead)
 }
}

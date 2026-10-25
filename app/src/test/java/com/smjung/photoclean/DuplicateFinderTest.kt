package com.smjung.photoclean

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class DuplicateFinderTest {
 private fun find(files:Map<String,ByteArray>,unknown:Set<String> = emptySet()):DuplicateFinder.Result<String> =
  DuplicateFinder.find(files.keys.toList(),{ if(it in unknown) null else files.getValue(it).size.toLong() },
   { ByteArrayInputStream(files.getValue(it)) },{}, { _,_,_,_,_ -> })
 @Test fun uniqueSizesAvoidAllContentReads() {
  val result=find(mapOf("a" to ByteArray(100),"b" to ByteArray(200),"c" to ByteArray(300)))
  assertTrue(result.groups.isEmpty()); assertEquals(0L,result.bytesRead)
 }
 @Test fun sharedPrefixDoesNotMeanDuplicate() {
  val a=ByteArray(100000){7}; val b=a.copyOf().also { it[it.lastIndex]=8 }
  val result=find(linkedMapOf("original" to a,"copy" to a.copyOf(),"differentTail" to b))
  assertEquals(listOf(listOf("original","copy")),result.groups)
 }
 @Test fun differentPrefixesSkipFullRead() {
  val result=find(mapOf("a" to ByteArray(1000000){1},"b" to ByteArray(1000000){2}))
  assertTrue(result.groups.isEmpty()); assertEquals(131072L,result.bytesRead); assertEquals(0,result.verified)
 }
 @Test fun unknownSizeStillMatchesKnownSingleton() {
  val result=find(mapOf("known" to "same photo".toByteArray(),"unknown" to "same photo".toByteArray()),setOf("unknown"))
  assertEquals(1,result.groups.size); assertEquals(2,result.groups.single().size)
 }
 @Test fun unreadableItemsAreNotDuplicates() {
  val result=DuplicateFinder.find(listOf("good","bad"),{ 100L },
   { if(it=="bad") throw IOException("removed") else ByteArrayInputStream(ByteArray(100)) },{}, { _,_,_,_,_ -> })
  assertTrue(result.groups.isEmpty()); assertEquals(1,result.errors)
 }
 @Test(expected=CancellationException::class) fun cancellationPropagates() {
  DuplicateFinder.find(listOf("a","b"),{ 100L },{ ByteArrayInputStream(ByteArray(100)) },
   { throw CancellationException() },{ _,_,_,_,_ -> })
 }
}

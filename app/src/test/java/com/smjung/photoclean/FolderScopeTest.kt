package com.smjung.photoclean
import org.junit.Assert.*
import org.junit.Test
class FolderScopeTest {
 @Test fun primaryChildIsMapped() { assertEquals("primary:DCIM/Camera/a.jpg",FolderScope.documentId("primary:DCIM","external_primary","DCIM/Camera/","a.jpg")) }
 @Test fun sdCardUsesGrantedVolumeSpelling() { assertEquals("ABCD-1234:Pictures/한글.jpg",FolderScope.documentId("ABCD-1234:Pictures","abcd-1234","Pictures/","한글.jpg")) }
 @Test fun siblingFolderCannotUseGrant() { assertNull(FolderScope.documentId("primary:DCIM","external_primary","DCIM2/","a.jpg")) }
 @Test fun differentVolumeCannotUseGrant() { assertNull(FolderScope.documentId("primary:","abcd-1234","DCIM/","a.jpg")) }
 @Test fun traversalAndFileAsFolderAreRejected() {
  assertNull(FolderScope.documentId("primary:DCIM","external_primary","DCIM/../Pictures/","a.jpg"))
  assertNull(FolderScope.documentId("primary:DCIM","external_primary","DCIM/","../a.jpg"))
  assertNull(FolderScope.documentId("primary:DCIM","external_primary","","DCIM"))
 }
 @Test fun rootGrantIncludesNestedFile() { assertEquals("primary:Pictures/a.jpg",FolderScope.documentId("primary:","external_primary","Pictures/","a.jpg")) }
}

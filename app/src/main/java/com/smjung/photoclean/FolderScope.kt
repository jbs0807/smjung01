package com.smjung.photoclean

/** Map a media file only into a user-authorized external-storage subtree. */
internal object FolderScope {
 fun documentId(treeId:String,volume:String,path:String,name:String):String? {
  if(!treeId.contains(':') || name.isEmpty() || name in listOf(".","..") || name.contains('/') || name.contains('\\')) return null
  val rootVolume=treeId.substringBefore(':')
  val fileVolume=if(volume=="external_primary") "primary" else volume
  if(!rootVolume.equals(fileVolume,true)) return null
  val folder=path.trim('/')
  val root=treeId.substringAfter(':').trim('/')
  if((folder.split('/')+root.split('/')).any { it=="." || it==".." || it.contains('\\') }) return null
  val target=if(folder.isEmpty()) name else "$folder/$name"
  if(root.isNotEmpty() && !target.startsWith("$root/")) return null
  return "$rootVolume:$target"
 }
}

package com.privatespace.android.data
data class VaultTopic(val id:String,val name:String,val createdAt:Long,val updatedAt:Long)
data class VaultFile(val id:String,val topicId:String,val displayName:String,val mimeType:String,val sizeBytes:Long,val addedAt:Long)
data class TopicSummary(val topic:VaultTopic,val fileCount:Int,val totalBytes:Long)

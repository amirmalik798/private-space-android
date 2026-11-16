package com.privatespace.android.data
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.privatespace.android.security.VaultCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
class VaultRepository(private val c:Context){
 private val root=File(c.filesDir,"vault").apply{mkdirs()};private val prefs=c.getSharedPreferences("private_space_vault",Context.MODE_PRIVATE)
 @Synchronized fun topics():List<VaultTopic> = runCatching{val a=JSONArray(prefs.getString("topics","[]"));(0 until a.length()).map{val o=a.getJSONObject(it);VaultTopic(o.getString("id"),o.getString("name"),o.getLong("created"),o.getLong("updated"))}}.getOrDefault(emptyList())
 @Synchronized fun files():List<VaultFile> = runCatching{val a=JSONArray(prefs.getString("files","[]"));(0 until a.length()).map{val o=a.getJSONObject(it);VaultFile(o.getString("id"),o.getString("topic"),o.getString("name"),o.getString("mime"),o.getLong("size"),o.getLong("added"))}}.getOrDefault(emptyList())
 fun summaries()=topics().map{t->val fs=files().filter{it.topicId==t.id};TopicSummary(t,fs.size,fs.sumOf{it.sizeBytes})}
 private fun saveTopics(xs:List<VaultTopic>){val a=JSONArray();xs.forEach{a.put(JSONObject().put("id",it.id).put("name",it.name).put("created",it.createdAt).put("updated",it.updatedAt))};check(prefs.edit().putString("topics",a.toString()).commit())}
 private fun saveFiles(xs:List<VaultFile>){val a=JSONArray();xs.forEach{a.put(JSONObject().put("id",it.id).put("topic",it.topicId).put("name",it.displayName).put("mime",it.mimeType).put("size",it.sizeBytes).put("added",it.addedAt))};check(prefs.edit().putString("files",a.toString()).commit())}
 @Synchronized fun createTopic(name:String):VaultTopic{val n=name.trim();require(n.isNotEmpty()&&n.length<=64){"Enter a topic name up to 64 characters."};require(topics().none{it.name.equals(n,true)}){"Topic already exists."};val now=System.currentTimeMillis();val t=VaultTopic(UUID.randomUUID().toString(),n,now,now);saveTopics(topics()+t);return t}
 @Synchronized fun addFile(topic:String,uri:Uri):VaultFile{require(topics().any{it.id==topic});val name=runCatching{c.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}.getOrNull()?: "file-${System.currentTimeMillis()}";val safe=name.replace(Regex("[\\\\/:*?\\\"<>|\\\\p{Cntrl}]"),"_").take(160);val mime=c.contentResolver.getType(uri)?:MimeTypeMap.getSingleton().getMimeTypeFromExtension(safe.substringAfterLast(".",""))?:"application/octet-stream";val id=UUID.randomUUID().toString();val target=File(root,"$id.psv");var count=0L;val src=c.contentResolver.openInputStream(uri)?:error("Cannot read source file.");src.use{raw->val input=object:java.io.FilterInputStream(raw){override fun read():Int{val v=super.read();if(v>=0)count++;return v};override fun read(b:ByteArray,o:Int,l:Int):Int{val n=super.read(b,o,l);if(n>0)count+=n;return n}};FileOutputStream(target).use{VaultCrypto.encrypt(input,it)}};val f=VaultFile(id,topic,safe,mime,count,System.currentTimeMillis());try{saveFiles(files()+f);saveTopics(topics().map{if(it.id==topic)it.copy(updatedAt=f.addedAt)else it});return f}catch(e:Throwable){target.delete();throw e}}
 fun decrypt(id:String):File{val f=files().firstOrNull{it.id==id}?:error("File not found.");val dir=File(c.cacheDir,"outgoing").apply{mkdirs()};dir.listFiles()?.forEach{it.delete()};val ext=f.displayName.substringAfterLast(".","").takeIf{it.matches(Regex("[A-Za-z0-9]{1,10}"))};val target=File(dir,if(ext==null)f.id else "${f.id}.$ext");FileInputStream(File(root,"${f.id}.psv")).use{i->FileOutputStream(target).use{o->VaultCrypto.decrypt(i,o)}};return target}
 @Synchronized fun deleteFile(id:String){val f=files().firstOrNull{it.id==id}?:return;File(root,"$id.psv").delete();saveFiles(files().filterNot{it.id==id});saveTopics(topics().map{if(it.id==f.topicId)it.copy(updatedAt=System.currentTimeMillis())else it})}
 @Synchronized fun deleteTopic(id:String){files().filter{it.topicId==id}.forEach{File(root,"${it.id}.psv").delete()};saveFiles(files().filterNot{it.topicId==id});saveTopics(topics().filterNot{it.id==id})}
 fun export(id:String,d:DocumentFile){val clear=decrypt(id);try{val out=c.contentResolver.openOutputStream(d.uri,"w")?:error("Cannot write export.");out.use{FileInputStream(clear).copyTo(it)}}finally{clear.delete()}}
 fun share(id:String):Pair<Uri,String>{val f=files().first{it.id==id};val tmp=decrypt(id);return androidx.core.content.FileProvider.getUriForFile(c,"com.privatespace.android.fileprovider",tmp) to f.mimeType}
}

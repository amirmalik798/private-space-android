package com.privatespace.android.security
import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
object PasswordManager {
 private const val PREFS="private_space_auth";private const val ROUNDS=310000
 private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
 fun configured(c:Context)=prefs(c).contains("salt")&&prefs(c).contains("hash")
 private fun derive(p:CharArray,s:ByteArray):ByteArray{val spec=PBEKeySpec(p,s,ROUNDS,256);return try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}finally{spec.clearPassword()}}
 fun set(c:Context,p:CharArray){require(p.size>=8){"Use at least 8 characters."};val salt=ByteArray(16).also{SecureRandom().nextBytes(it)};val h=derive(p,salt);check(prefs(c).edit().putString("salt",Base64.encodeToString(salt,Base64.NO_WRAP)).putString("hash",Base64.encodeToString(h,Base64.NO_WRAP)).putInt("failures",0).putLong("lock",0).commit());p.fill(0.toChar());h.fill(0)}
 fun verify(c:Context,p:CharArray):Boolean{val q=prefs(c);val s=Base64.decode(q.getString("salt",null)?:return false,Base64.NO_WRAP);val e=Base64.decode(q.getString("hash",null)?:return false,Base64.NO_WRAP);val a=derive(p,s);p.fill(0.toChar());val ok=MessageDigest.isEqual(e,a);e.fill(0);a.fill(0);return ok}
 fun locked(c:Context)=prefs(c).getLong("lock",0L)
 fun fail(c:Context):Long{val q=prefs(c);val n=q.getInt("failures",0)+1;val d=when{n<5->0L;n<8->30000L;n<11->120000L;else->600000L};val until=System.currentTimeMillis()+d;q.edit().putInt("failures",n).putLong("lock",until).apply();return until}
 fun clear(c:Context){prefs(c).edit().putInt("failures",0).putLong("lock",0L).apply()}
}

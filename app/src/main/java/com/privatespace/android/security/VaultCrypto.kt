package com.privatespace.android.security
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
object VaultCrypto {
 private const val ALIAS="private_space_aes_v1"
 private fun key():SecretKey {val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};(ks.getKey(ALIAS,null) as? SecretKey)?.let{return it};return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())}.generateKey()}
 fun encrypt(input:InputStream,output:OutputStream){val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());val iv=c.iv;require(iv.size==12);output.write(iv);CipherOutputStream(output,c).use{input.copyTo(it,65536)}}
 fun decrypt(input:InputStream,output:OutputStream){val iv=ByteArray(12);var off=0;while(off<12){val n=input.read(iv,off,12-off);require(n>0){"Encrypted file truncated."};off+=n};val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv));CipherInputStream(input,c).use{it.copyTo(output,65536)}}
}

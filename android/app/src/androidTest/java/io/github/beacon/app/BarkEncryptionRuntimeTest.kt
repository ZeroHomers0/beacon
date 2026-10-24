package io.github.beacon.app

import androidx.test.platform.app.InstrumentationRegistry
import io.github.beacon.app.data.local.RuntimeMessageStore
import io.github.beacon.app.data.local.TransportSecretStore
import io.github.beacon.app.data.repository.RuntimeRepository
import io.github.beacon.app.domain.model.IncomingMessage
import io.github.beacon.app.domain.model.TransportSettings
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BarkEncryptionRuntimeTest {
    @Test fun binderSaveDecryptsHistoryAndClearPreservesConnection() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val repository=RuntimeRepository()
        val key="1234567890abcdef"
        val iv="abcdef1234567890"
        val id="bark-encryption-"+UUID.randomUUID()
        val server="bark-encryption-test"
        fun awaitIdle(){
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
            while((!repository.state.value.runtimeConnected||!repository.state.value.messagesLoaded||!repository.state.value.connectionSummaryLoaded||repository.state.value.messageBusy||repository.state.value.transportBusy)&&System.nanoTime()<end)Thread.sleep(20)
            assertTrue(repository.state.value.runtimeConnected)
            assertFalse(repository.state.value.messageBusy||repository.state.value.transportBusy)
        }
        fun configured():Boolean? {
            val answer=AtomicReference<Boolean?>();val latch=CountDownLatch(1)
            instrumentation.runOnMainSync{repository.barkEncryptionConfigured{answer.set(it);latch.countDown()}}
            assertTrue(latch.await(15,TimeUnit.SECONDS));return answer.get()
        }
        fun save(aes:String,defaultIv:String):String {
            val outcome=AtomicReference("");val failure=AtomicReference("");val latch=CountDownLatch(1)
            awaitIdle()
            instrumentation.runOnMainSync{repository.barkEncryption(aes,defaultIv,onFailure={failure.set(it);latch.countDown()}){outcome.set(it);latch.countDown()}}
            assertTrue(latch.await(15,TimeUnit.SECONDS));assertEquals("",failure.get());awaitIdle();return outcome.get()
        }
        RuntimeMessageStore(context).use { store ->
            val transport=TransportSettings("http://127.0.0.1:1","f".repeat(64))
            TransportSecretStore(context).save(transport)
            repository.start(context)
            try {
                awaitIdle()
                save("","")
                assertEquals(false,configured())
                val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key.toByteArray(),"AES"),IvParameterSpec(iv.toByteArray()))
                val metadata=JSONObject().put("ciphertext",Base64.getEncoder().encodeToString(cipher.doFinal("""{"title":"解密测试","body":"历史正文"}""".toByteArray()))).toString()
                store.save(IncomingMessage(server,id,"","encrypted",bark=metadata))
                assertEquals("加密消息",store.detail(server,id)!!.title)
                val result=save(key,iv)
                assertTrue(result.contains("解密 1 条历史消息"))
                assertEquals(true,configured())
                assertEquals("历史正文",store.detail(server,id)!!.body)
                assertFalse(context.getSharedPreferences("transport_secret",0).all.toString().contains(key))
                save("","")
                assertEquals(false,configured())
                assertEquals(transport,TransportSecretStore(context).load())
                assertEquals("历史正文",store.detail(server,id)!!.body)
            } finally {
                val cleared=CountDownLatch(1)
                instrumentation.runOnMainSync{repository.clearConnection{cleared.countDown()}}
                cleared.await(15,TimeUnit.SECONDS)
                instrumentation.runOnMainSync{repository.close()}
                store.remove(server,id);store.ackCompleted(server,id)
            }
        }
    }
}

package com.ivi.car.navigation.viewmodel

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import com.ivi.recognition.SpeechResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ivi.recognition.IviSpeechRecognitionManager
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import java.util.Base64
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@HiltViewModel
class AudioViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel(), IviSpeechRecognitionManager.ISpeechRecognition {
    private val TAG = this.javaClass.name
    private val PACKAGE_NAME = this.javaClass.packageName
    private var mMediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var serviceConnectionRequested = false
    private lateinit var speechRecognitionManager: IviSpeechRecognitionManager
    private val mAudioList: Queue<ByteArray> = ConcurrentLinkedQueue()
    var isVoiceInstructionsMuted = true

    fun connectService() {
        if (serviceConnectionRequested) {
            return
        }
        serviceConnectionRequested = true
        speechRecognitionManager = IviSpeechRecognitionManager.getInstance(context)
        speechRecognitionManager.connectTssService(object :
            IviSpeechRecognitionManager.ITtsServiceConnection {
            override fun onTtsRecognitionServiceConnected() {
                Log.d(TAG, "onTtsRecognitionServiceConnected: ")
                speechRecognitionManager.registerTextToSpeechListener(
                    this@AudioViewModel,
                    PACKAGE_NAME
                )
            }

            override fun onTtsRecognitionServiceDisconnected() {
                Log.d(TAG, "onTtsRecognitionServiceDisconnected: ")
                serviceConnectionRequested = false
                speechRecognitionManager.unregisterTextToSpeechRecognitionListener(
                    this@AudioViewModel,
                    PACKAGE_NAME
                )
            }
        })
    }

    fun requestVoiceInstructionsWithSsml(ssmlAnnouncement: String) {
        runCatching {
            requestTtsWithSsml(parseSsmlXml(ssmlAnnouncement))
        }.onFailure { error ->
            Log.e(TAG, "Unable to process voice instruction", error)
        }
    }

    fun setVoiceInstructionsMuted(){
        isVoiceInstructionsMuted = !isVoiceInstructionsMuted
        if(isVoiceInstructionsMuted && isPlaying){
            stop()
            clearAudio()
        }
    }

    private fun parseSsmlXml(ssmlAnnouncement: String): String {
        val documentFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = documentFactory.newDocumentBuilder()
            .parse(InputSource(StringReader(ssmlAnnouncement)))
        val tFactory = TransformerFactory.newInstance()
        val transformer: Transformer = tFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        val sw = StringWriter()
        val result = StreamResult(sw)

        val nl = doc.getElementsByTagName("prosody")
        var source: DOMSource? = null
        for (x in 0 until nl.length) {
            val e = nl.item(x)
            if (e is Element) {
                source = DOMSource(e)
                break
            }
        }
        transformer.transform(source ?: DOMSource(doc.documentElement), result)
        return result.writer.toString()
    }

    private fun requestTtsWithSsml(stringXml: String, normalizerString: String = "") {
        if (stringXml.contains("<say-as")) {
            val startAdd = stringXml.indexOf("<say-as")
            val endAdd = stringXml.indexOf("</say-as>")
            if (endAdd < startAdd) {
                Log.w(TAG, "Ignoring malformed say-as element")
                return
            }
            val address = stringXml.substring(startAdd, endAdd + 9) + "\n"
            val beginProsody = stringXml.substring(0, startAdd) + "\n"
            val endProsody = stringXml.substring(endAdd + 9)
            requestTtsWithSsml(endProsody, normalizerString + beginProsody + address)
            return
        }
        val ssml =
            """<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="en-US"><voice name="en-US-AvaMultilingualNeural">
            ${
                if (normalizerString.isNotEmpty()) {
                    normalizerString + stringXml
                } else {
                    stringXml
                }
            }</voice></speak>"""
        if (this::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.requestConvertTextToSpeech(ssml, null, PACKAGE_NAME, true)
        }
    }

    @SuppressLint("SuspiciousIndentation")
    @Synchronized
    private fun playSound(uri: String) {
        try {
            stop()
            val player = MediaPlayer()
            mMediaPlayer = player
            val mediaPath = Uri.parse(uri)
            player.setDataSource(context, mediaPath)
            player.prepare()
            player.start()
            player.setOnCompletionListener {
                if (mAudioList.size > 0) {
                    playSound(
                        URI_RESOURCE + Base64.getEncoder()
                            .encodeToString(mAudioList.poll())
                    )
                } else {
                    isPlaying = false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun stop() {
        val player = mMediaPlayer ?: return
        runCatching {
            if (player.isPlaying) {
                player.stop()
            }
            player.reset()
        }
        player.release()
        mMediaPlayer = null
        isPlaying = false
    }

    @Synchronized
    private fun onAudioNotifyChange() {
        if (!isPlaying) {
            if (mAudioList.size > 0) {
                playSound(
                    URI_RESOURCE + Base64.getEncoder()
                        .encodeToString(mAudioList.poll())
                )
                isPlaying = true
            } else {
                isPlaying = false
            }
        }
    }

    fun clearAudio() {
        if (mAudioList.size > 0) {
            mAudioList.clear()
        }
    }

    companion object {
        private val URI_RESOURCE = "data:audio/mp3;base64,"
    }

    override fun onSpeechRecognitionCallback(speechRecognitionResponse: SpeechResponse?) {
        Log.d(TAG, "Text-to-speech audio received")
        if (speechRecognitionResponse != null) {
            mAudioList.add(speechRecognitionResponse.speech)
            onAudioNotifyChange()
        }
    }

    override fun onSpeechRecognitionError(errorCode: Int, message: String?) {
        Log.d(TAG, "onSpeechRecognitionError: $message")
    }

    override fun onCleared() {
        stop()
        clearAudio()
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.unregisterTextToSpeechRecognitionListener(this, PACKAGE_NAME)
        }
        serviceConnectionRequested = false
        super.onCleared()
    }
}
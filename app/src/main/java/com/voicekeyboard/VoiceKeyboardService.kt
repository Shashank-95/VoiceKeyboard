package com.voicekeyboard

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

class VoiceKeyboardService : InputMethodService() {

    private lateinit var keyboardView: View
    private lateinit var recordButton: Button
    private lateinit var statusText: TextView

    private var isRecording = false
    private var webSocket: WebSocketClient? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    private val DEEPGRAM_API_KEY = "e11232ed538225a44c7066ba0b996f320c8aa48a"
    private val DEEPGRAM_URL = "wss://api.deepgram.com/v1/listen?model=nova-2&encoding=linear16&sample_rate=16000&channels=1&interim_results=true"

    override fun onCreateInputView(): View {
        keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null)
        recordButton = keyboardView.findViewById(R.id.record_button)
        statusText = keyboardView.findViewById(R.id.status_text)

        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
        return keyboardView
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Microphone Permission Missing!"
            return
        }

        isRecording = true
        recordButton.text = getString(R.string.stop_recording)
        recordButton.setBackgroundColor(android.graphics.Color.parseColor("#F44336")) // Red
        statusText.text = "Connecting..."

        connectWebSocket()
    }

    private fun stopRecording() {
        isRecording = false
        recordButton.text = getString(R.string.start_recording)
        recordButton.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50")) // Green
        statusText.text = "Status: Ready"

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        webSocket?.close()
    }

    private fun connectWebSocket() {
        val uri = URI(DEEPGRAM_URL)
        val headers = mutableMapOf("Authorization" to "Token $DEEPGRAM_API_KEY")

        webSocket = object : WebSocketClient(uri, headers) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Handler(Looper.getMainLooper()).post {
                    statusText.text = "Listening..."
                }
                startAudioCapture()
            }

            override fun onMessage(message: String?) {
                message?.let {
                    try {
                        val json = JSONObject(it)
                        if (json.has("channel")) {
                            val channel = json.getJSONObject("channel")
                            val alternatives = channel.getJSONArray("alternatives")
                            if (alternatives.length() > 0) {
                                val transcript = alternatives.getJSONObject(0).getString("transcript")
                                val isFinal = json.getBoolean("is_final")
                                
                                if (transcript.isNotEmpty()) {
                                    Handler(Looper.getMainLooper()).post {
                                        // Insert text into the active text field
                                        val connection = currentInputConnection
                                        if (connection != null) {
                                            if (isFinal) {
                                                connection.commitText("$transcript ", 1)
                                            } else {
                                                // We can either set composing text or just commit
                                                connection.setComposingText(transcript, 1)
                                            }
                                        }
                                        if (isFinal) {
                                            statusText.text = "Listening..."
                                            connection?.finishComposingText()
                                        } else {
                                            statusText.text = "Hearing: $transcript"
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Handler(Looper.getMainLooper()).post {
                    if (isRecording) stopRecording()
                }
            }

            override fun onError(ex: Exception?) {
                ex?.printStackTrace()
                Handler(Looper.getMainLooper()).post {
                    statusText.text = "Error: ${ex?.message}"
                    if (isRecording) stopRecording()
                }
            }
        }
        webSocket?.connect()
    }

    private fun startAudioCapture() {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            audioRecord?.startRecording()

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0 && webSocket?.isOpen == true) {
                        // Send binary audio data to Deepgram over WebSocket
                        val audioData = buffer.copyOfRange(0, read)
                        webSocket?.send(audioData)
                    }
                }
            }
            recordingThread?.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
    }
}

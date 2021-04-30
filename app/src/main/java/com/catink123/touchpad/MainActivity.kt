package com.catink123.touchpad

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.Toast
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.lang.Exception
import java.net.URI
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import kotlin.concurrent.schedule

class MainActivity : AppCompatActivity() {
    private lateinit var webSocketClient: WebSocketClient
    private var maxX: Float = 0f
    private var maxY: Float = 0f
    private var x: Float = 0f
    private var y: Float = 0f
    private var movingX: Float = 0f
    private var movingY: Float = 0f
    private var mdX: Float = 0f
    private var mdY: Float = 0f
    private var savedX: Float = 0f
    private var savedY: Float = 0f
    private var isMoving: Boolean = false
    private var isConnected: Boolean = false
    private var canClick: Boolean = false
    private var canRightClick: Boolean = false
    private var rightClicked: Boolean = false
    private var canDoubleClick: Boolean = false
    private var dragging: Boolean = false
    private var clickedFirstTime: Boolean = false
    private lateinit var WEB_SOCKET_URL: String
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var spListener: SharedPreferences.OnSharedPreferenceChangeListener

    companion object {
//        const val WEB_SOCKET_URL = "ws://192.168.1.105:8765"
        const val TAG = "TouchPad"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            supportActionBar?.hide()
            if (Build.VERSION.SDK_INT < 30) {
                window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } else {
                val controller: WindowInsetsController? = window.insetsController;
                controller?.hide(WindowInsets.Type.statusBars())
            }
        }
        sharedPreferences = getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)
        spListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences: SharedPreferences, s: String ->
            Toast.makeText(this@MainActivity, "Preference changed", Toast.LENGTH_SHORT).show()
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(spListener)
        toucharea.setOnTouchListener { v, event ->
            v.performClick()
            // If the connection is established
            if (isConnected) {
                changeCoords(event.rawX, event.rawY)
                when (event.actionMasked) {
                    // If we have two fingers down, we are right-clicking
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (canRightClick) {
                            Timer("RightClick", false).cancel()
                            rightClicked = true
                            webSocketClient.send("RMBDown $x $y")
                            // Send RMBUp to complete the right-click
                            Timer("RightClick", false).schedule(20) {
                                webSocketClient.send("RMBUp")
                                rightClicked = false
                            }
                        }
                    }
                    MotionEvent.ACTION_DOWN -> {
                        canRightClick = true
                        // Time frame to right-click
                        Timer("RightClick", false).schedule(20) {
                            canRightClick = false
                            // If we are not right-clicking, it means we are left-clicking, or touching with one finger
                            if (!rightClicked) {
                                isMoving = true
                                // Set md(Mouse Down) coordinates to where the touch happened
                                mdX = event.rawX
                                mdY = event.rawY
                                // Save the current x and y, so we have an origin from where to move
                                savedX = x
                                savedY = y
                                canClick = true
                                // Time frame to release the finger to left-click
                                Timer("LeftClick", false).schedule(100) {
                                    canClick = false
                                }
                                if (canDoubleClick) {
                                    webSocketClient.send("LMBDown $x $y")
                                    dragging = true
                                    clickedFirstTime = false
                                }
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        isMoving = false
                        Timer("LeftClick", false).cancel()
                        // If we are drag'n'dropping, we want to to send LMBUp to complete our dragging
                        if (canClick && !rightClicked) {
                            clickedFirstTime = true
                            canDoubleClick = true
                            Timer("DoubleClick", false).schedule(150) {
                                canDoubleClick = false
                                clickedFirstTime = false
                                // We don't want to complete click before drag'n'drop, so we check if we are drag'n'dropping
                                if (!dragging) {
                                    webSocketClient.send("LMBDown $x $y")
                                    Timer("LeftClick", false).schedule(20) {
                                        webSocketClient.send("LMBUp")
                                    }
                                }
                            }
                        }

                        if (dragging) {
                            webSocketClient.send("LMBUp")
                            dragging = false
                        }
                    }
                }
            }
            true
        }
        leftButton.setOnTouchListener { v, event ->
            v.performClick()
            if (isConnected) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> webSocketClient.send("LMBDown $x $y")
                    MotionEvent.ACTION_UP -> webSocketClient.send("LMBUp")
                }
            }
            true
        }
        rightButton.setOnTouchListener { v, event ->
            v.performClick()
            if (isConnected) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> webSocketClient.send("RMBDown $x $y")
                    MotionEvent.ACTION_UP -> webSocketClient.send("RMBUp")
                }
            }
            true
        }
        updateServerURL()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (webSocketClient.connection.isOpen) {
            webSocketClient.send("Client Disconnected")
            webSocketClient.close()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.settings -> {
                val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                startActivity(intent)
            }
            R.id.reconnect -> {
                reconnectWebSocket()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        initWebSocket()
        sharedPreferences.registerOnSharedPreferenceChangeListener(spListener)
    }

    override fun onPause() {
        super.onPause()
        if (webSocketClient.connection.isOpen) {
            webSocketClient.send("Client Disconnected")
            webSocketClient.close()
        }
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(spListener)
    }

    private fun updateServerURL() {
        var serverIP = getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE).getString("serverIP", null)
        WEB_SOCKET_URL = if (serverIP != null) {
            "ws://$serverIP:8765"
        } else {
            "ws://127.0.0.1:8765"
        }
    }

    private fun changeCoords(X: Float, Y: Float) {
        if (isMoving) {
            movingX = X
            movingY = Y
            x = savedX + (movingX - mdX)
            y = savedY + (movingY - mdY)
            if (x < 0) x = 0f
            if (y < 0) y = 0f
            if (x > maxX) x = maxX
            if (y > maxY) y = maxY
        }
        webSocketClient.send("Coords: $x $y")
        supportActionBar?.subtitle = "Coords: ${x.toInt()} ${y.toInt()}"
    }

    private fun reconnectWebSocket() {
        if (webSocketClient != null) {
            webSocketClient.close()
        }
        val pcURI: URI? = URI(WEB_SOCKET_URL)
        createWebSocketClient(pcURI)
        webSocketClient.connect()
    }

    private fun initWebSocket() {
        updateServerURL()
        val pcURI: URI? = URI(WEB_SOCKET_URL)
        createWebSocketClient(pcURI)
        webSocketClient.connect()
    }

    private fun createWebSocketClient(uri: URI?) {
        webSocketClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "onOpen")
                subscribe()
                isConnected = true
                Looper.prepare()
                Toast.makeText(this@MainActivity, "Connection established", Toast.LENGTH_SHORT).show()
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "onClose")
                unsubscribe()
                isConnected = false
                Looper.prepare()
                Toast.makeText(this@MainActivity, "Connection closed", Toast.LENGTH_SHORT).show()
            }

            override fun onMessage(message: String?) {
                Log.d(TAG, "onMessage: $message")
                setUpMaxCoords(message)
            }

            override fun onError(ex: Exception?) {
                if (ex?.message !== null) {
                    Log.e("createWebSocketClient", "onError: ${ex?.message}")
                    if (Looper.myLooper() == null) {
                        Looper.prepare()
                    }
                    Toast.makeText(this@MainActivity, "Error: ${ex?.message}", Toast.LENGTH_LONG).show()
                    isConnected = false
                }
            }
        }
    }

    private fun subscribe() {
        webSocketClient.send("Client Connected")
    }

    private fun unsubscribe() {
        webSocketClient.send("Client Disconnected")
    }

    private fun setUpMaxCoords(message: String?) {
        message?.let {
            val moshi = Moshi.Builder().build()
            val adapter: JsonAdapter<InputCoords> = moshi.adapter(InputCoords::class.java)
            val coords = adapter.fromJson(message)
            coords?.x?.let { gotX -> x = gotX }
            coords?.y?.let { gotY -> y = gotY - 0.1f }
            coords?.maxX?.let { gotMaxX -> maxX = gotMaxX - 0.1f }
            coords?.maxY?.let { gotMaxY -> maxY = gotMaxY - 0.1f }
        }
    }
}
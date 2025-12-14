package com.example.btmicrobitapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.microbitconnect.MicrobitManager

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var xyzText: TextView
    private lateinit var inputText: EditText
    private lateinit var connectButton: Button
    private lateinit var sendButton: Button

    private lateinit var microbitManager: MicrobitManager

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        startConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        xyzText = findViewById(R.id.xyzText)
        inputText = findViewById(R.id.inputText)
        connectButton = findViewById(R.id.btnConnect)
        sendButton = findViewById(R.id.btnSend)

        microbitManager = MicrobitManager(this)

        connectButton.setOnClickListener {
            ensurePermissionsThenConnect()
        }

        sendButton.setOnClickListener {
            val msg = inputText.text.toString()
            if (msg.isBlank()) {
                Toast.makeText(this, "Type a message first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            microbitManager.sendText(msg)
            Toast.makeText(this, "Sent: $msg", Toast.LENGTH_SHORT).show()
        }

        microbitManager.setOnUartLine { line ->
            runOnUiThread {
                // Expecting "x,y,z" from micro:bit
                val parts = line.trim().split(",")
                if (parts.size >= 3) {
                    xyzText.text = "X=${parts[0]}   Y=${parts[1]}   Z=${parts[2]}"
                } else {
                    xyzText.text = line.trim()
                }
            }
        }
    }

    private fun ensurePermissionsThenConnect() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
            perms += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        requestPerms.launch(perms.toTypedArray())
    }

    private fun startConnect() {
        statusText.text = "Status: Connecting..."
        microbitManager.scanAndConnect { msg ->
            runOnUiThread { statusText.text = "Status: $msg" }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        microbitManager.disconnect()
    }
}

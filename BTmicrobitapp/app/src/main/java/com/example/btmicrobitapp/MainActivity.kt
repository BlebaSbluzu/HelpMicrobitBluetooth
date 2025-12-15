package com.example.btmicrobitapp

import android.Manifest
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.microbitconnect.MicrobitManager
import kotlin.random.Random
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import android.util.Log
import android.widget.ImageView

class MainActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()
    private val TAG = "CowboyDuelGame"
    private lateinit var statusText: TextView

    private lateinit var cowboyimage: ImageView
    private lateinit var xyzText: TextView
    private lateinit var sendButton: Button
    private lateinit var leaderboardButton: Button
    private lateinit var microbitManager: MicrobitManager
    private var isValid : Boolean = false

    private var playerShotTime: Long = 0L
    private var gameStartTime: Long = 0L
    private var botReactionTime: Float = 0f
    private var countdownTimer: CountDownTimer? = null
    private val MAGNITUDE_THRESHOLD = 1500f

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        startConnect()
    }

    data class DuelResult(
        val playerTimeSeconds: Float = 0f,
        val botTimeSeconds: Float = 0f,
        val winner: String = "",
        val timestamp: Long = 0L
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cowboyimage = findViewById(R.id.imageView)
        statusText = findViewById(R.id.statusText)
        xyzText = findViewById(R.id.xyzText)
        sendButton = findViewById(R.id.btnSend)
        leaderboardButton = findViewById(R.id.button)

        microbitManager = MicrobitManager(this)

        ensurePermissionsThenConnect()

        sendButton.setOnClickListener {
            if (!isValid) {
                startGame()
                Toast.makeText(this, "Duel starting...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Duel in progress!", Toast.LENGTH_SHORT).show()
            }
        }

        leaderboardButton.setOnClickListener {
            val intent = Intent(this, LeaderboardActivity::class.java)
            startActivity(intent)
        }


        setupMicrobitListeners()
    }

    private fun startGame(){

        cowboyimage.setImageResource(R.drawable.cowboy1)


        sendButton.visibility = View.INVISIBLE
        leaderboardButton.visibility = View.INVISIBLE
        isValid = false
        playerShotTime = 0L
        statusText.text = "Status: Wait for 'SHOOT'..."

        val minBotMs = 750
        val maxBotMs = 1800
        botReactionTime = (minBotMs + Random.nextFloat() * (maxBotMs - minBotMs)) / 1000f

        val waitTimeMs = Random.nextLong(3000, 6001)

        countdownTimer = object : CountDownTimer(waitTimeMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
            }
            override fun onFinish() {
                val whistle = MediaPlayer.create(this@MainActivity, R.raw.whistle)
                whistle.start()

                statusText.text = "Status: SHOOT!"
                gameStartTime = System.currentTimeMillis()
                isValid = true

                microbitManager.sendText("SHOOT")
            }
        }.start()
    }

    private fun checkWinner (playerMagnitude : Float) {

        cowboyimage.setImageResource(R.drawable.cowboyshoot)

        if (!isValid) return


        if (gameStartTime == 0L) {
            statusText.text = "Result: Too soon! (False start)"
            isValid = false
            countdownTimer?.cancel()
            sendButton.visibility = View.VISIBLE
            leaderboardButton.visibility = View.VISIBLE
            return
        }

        if (playerMagnitude < MAGNITUDE_THRESHOLD) {
            return
        }

        val playerReactionMs = System.currentTimeMillis() - gameStartTime
        val playerSpeed = playerReactionMs / 1000f

        isValid = false
        val gun = MediaPlayer.create(this@MainActivity, R.raw.gun)
        gun.start()
        val winnerName = if (playerSpeed < botReactionTime) {
            "Player"
        } else {
            "Bot"
        }

        if(winnerName == "Player"){
            cowboyimage.setImageResource(R.drawable.cowboydead)

        }
        else{
            cowboyimage.setImageResource(R.drawable.youlose)

        }
        statusText.text = String.format("Result: %s Wins! Time: %.3f s (Bot: %.3f s)", winnerName, playerSpeed, botReactionTime)

        saveDuelResult(playerSpeed, botReactionTime, winnerName)

        sendButton.visibility = View.VISIBLE
        leaderboardButton.visibility = View.VISIBLE
    }

    private fun saveDuelResult(playerSpeed: Float, botSpeed: Float, winner: String) {
        val result = DuelResult(
            playerTimeSeconds = playerSpeed,
            botTimeSeconds = botSpeed,
            winner = winner
        )

        db.collection("duel_results")
            .add(result)
            .addOnSuccessListener { documentReference ->
                Log.d(TAG, "Duel result saved with ID: ${documentReference.id}")
                Toast.makeText(this, "Result saved!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Error adding document", e)
                Toast.makeText(this, "Failed to save result.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun setupMicrobitListeners() {
        microbitManager.setOnUartLine { line ->
            runOnUiThread {
                val trimmedLine = line.trim()

                val parts = trimmedLine.split(",")
                if (parts.size >= 3) {
                    val x = parts[0].toFloatOrNull() ?: 0f
                    val y = parts[1].toFloatOrNull() ?: 0f
                    val z = parts[2].toFloatOrNull() ?: 0f

                    val magnitude = kotlin.math.sqrt(x * x + y * y + z * z)

                    xyzText.text = String.format("X=%.0f Y=%.0f Z=%.0f | Mag=%.0f", x, y, z, magnitude)

                    if (isValid && playerShotTime == 0L) {
                        checkWinner(magnitude)
                        if (!isValid) {
                            playerShotTime = System.currentTimeMillis()
                        }
                    }
                }
            }
        }
    }



    private fun sendMSG() {
        sendButton.setOnClickListener {
            if (!isValid) {
                startGame()
                Toast.makeText(this, "Duel starting...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Duel in progress!", Toast.LENGTH_SHORT).show()
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
        countdownTimer?.cancel()
        microbitManager.disconnect()
    }
}
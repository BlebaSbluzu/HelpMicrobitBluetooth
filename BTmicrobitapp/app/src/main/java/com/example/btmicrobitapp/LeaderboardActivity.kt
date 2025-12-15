package com.example.btmicrobitapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.widget.Button
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

data class DuelResult(
    val playerTimeSeconds: Float = 0f,
    val botTimeSeconds: Float = 0f,
    val winner: String = "",
    val timestamp: Long = 0L
)

class LeaderboardActivity : AppCompatActivity() {
    private val db = FirebaseFirestore.getInstance()

    private lateinit var resultsTextView: TextView
    private val TAG = "LeaderboardActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.view_leaderboard)

        val backButton: Button = findViewById(R.id.backButton)
        resultsTextView = findViewById(R.id.leaderboardResultsText)

        backButton.setOnClickListener {
            finish()
        }

        fetchPlayerWinsLeaderboard()
    }

    private fun fetchPlayerWinsLeaderboard() {
        resultsTextView.text = "Loading top 10 player wins..."

        db.collection("duel_results")
            .whereEqualTo("winner", "Player")
            .orderBy("playerTimeSeconds", Query.Direction.ASCENDING)
            .limit(10)
            .get()
            .addOnSuccessListener { result ->
                val sb = StringBuilder("🏆 TOP 10 PLAYER WINS 🏆\n\n")
                var rank = 1

                if (result.isEmpty) {
                    sb.append("No player victories recorded yet.")
                } else {
                    for (document in result) {
                        val duel = document.toObject(DuelResult::class.java)

                        sb.append(
                            String.format(
                                "#%d. Time: %.3f s\n",
                                rank++,
                                duel.playerTimeSeconds
                            )
                        )
                    }
                }
                resultsTextView.text = sb.toString()
                Toast.makeText(this, "Leaderboard updated!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { exception ->
                resultsTextView.text = "Error fetching data. Check Android Studio Logcat for details."
                Log.e(TAG, "LEADERBOARD FETCH FAILED.", exception)
                Log.e(TAG, "COMMON FIX: Check Firebase Console -> Firestore -> Indexes. The query requires a composite index on 'winner' (ASC) and 'playerTimeSeconds' (ASC).")
                Toast.makeText(this, "Failed to load leaderboard. See logs.", Toast.LENGTH_LONG).show()
            }
    }
}
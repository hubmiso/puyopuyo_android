package com.example.puyopuyo

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Puyo Puyo 메인 액티비티
 * - 세로 화면 고정 및 몰입형 풀스크린(상태바/내비바 숨김)
 * - 버추얼 컨트롤러 버튼(좌, 우, 소프트 드롭, 하드 드롭, 시계/반시계 회전, 재시작)
 * - 즉각적인 터치 반응 및 버튼 홀드 시 연속 입력(Repeat Click) 지원
 */
class MainActivity : AppCompatActivity(), GameView.GameListener {

    private lateinit var gameView: GameView
    private lateinit var tvScore: TextView
    private lateinit var tvChain: TextView
    private lateinit var ivNextMain: ImageView
    private lateinit var ivNextSub: ImageView

    private val repeatHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 풀스크린 몰입 모드 설정
        enableImmersiveMode()

        // 뷰 초기화
        gameView = findViewById(R.id.gameView)
        tvScore = findViewById(R.id.tvScore)
        tvChain = findViewById(R.id.tvChain)
        ivNextMain = findViewById(R.id.ivNextMain)
        ivNextSub = findViewById(R.id.ivNextSub)

        gameView.gameListener = this

        // 하단 가상 D-패드 조작 버튼 바인딩
        setupControllerButtons()
    }

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControllerButtons() {
        val btnLeft: ImageButton = findViewById(R.id.btnLeft)
        val btnRight: ImageButton = findViewById(R.id.btnRight)
        val btnDown: ImageButton = findViewById(R.id.btnDown)
        val btnRotateCW: ImageButton = findViewById(R.id.btnRotateCW)
        val btnRotateCCW: ImageButton = findViewById(R.id.btnRotateCCW)
        val btnHardDrop: Button = findViewById(R.id.btnHardDrop)
        val btnRestart: Button = findViewById(R.id.btnRestart)

        // 좌/우/하강 버튼은 손가락을 누르고 있을 때 연속 입력(Repeat) 처리
        setupRepeatButton(btnLeft) { gameView.moveLeft() }
        setupRepeatButton(btnRight) { gameView.moveRight() }
        setupRepeatButton(btnDown, initialDelay = 150L, repeatInterval = 60L) {
            gameView.stepSoftDrop()
        }

        // 회전 및 특수 조작 버튼
        btnRotateCW.setOnClickListener { gameView.rotateClockwise() }
        btnRotateCCW.setOnClickListener { gameView.rotateCounterClockwise() }
        btnHardDrop.setOnClickListener { gameView.hardDrop() }
        btnRestart.setOnClickListener { gameView.startNewGame() }
    }

    /**
     * 모바일 게임패드 전용 홀드 연속 입력 헬퍼
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupRepeatButton(
        button: View,
        initialDelay: Long = 200L,
        repeatInterval: Long = 90L,
        action: () -> Unit
    ) {
        var isPressed = false
        val repeatRunnable = object : Runnable {
            override fun run() {
                if (isPressed) {
                    action()
                    repeatHandler.postDelayed(this, repeatInterval)
                }
            }
        }

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isPressed = true
                    action() // 최초 1회 즉시 실행
                    repeatHandler.postDelayed(repeatRunnable, initialDelay)
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(50).start()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    repeatHandler.removeCallbacks(repeatRunnable)
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start()
                    true
                }
                else -> false
            }
        }
    }

    // ===== GameListener 구현부 =====
    override fun onScoreChanged(score: Int, chain: Int) {
        tvScore.text = "점수: $score"
        tvChain.text = if (chain > 0) "$chain 연쇄!" else "연쇄: 0"
    }

    override fun onNextPuyoChanged(mainColor: Int, subColor: Int) {
        setPuyoColorDrawable(ivNextMain, mainColor)
        setPuyoColorDrawable(ivNextSub, subColor)
    }

    override fun onGameOver(finalScore: Int) {
        tvScore.text = "점수: $finalScore (GAME OVER)"
    }

    private fun setPuyoColorDrawable(iv: ImageView, colorType: Int) {
        val colorHex = when (colorType) {
            GameView.COLOR_RED -> "#EF4444"
            GameView.COLOR_BLUE -> "#3B82F6"
            GameView.COLOR_GREEN -> "#10B981"
            GameView.COLOR_YELLOW -> "#F59E0B"
            else -> "#64748B"
        }
        iv.setColorFilter(Color.parseColor(colorHex))
    }

    override fun onPause() {
        super.onPause()
        gameView.pauseGame()
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        gameView.resumeGame()
    }
}

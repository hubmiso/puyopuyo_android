package com.example.puyopuyo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.LinkedList
import java.util.Queue
import kotlin.random.Random

/**
 * 뿌요뿌요 게임의 핵심 SurfaceView 게임 루프 및 렌더링/로직 엔진
 * - 외부 라이브러리 없이 Android 표준 SDK만 사용
 * - 6열 x 12행 그리드
 * - 실시간 60FPS SurfaceHolder 스레드 렌더링
 * - 4개 이상 연결 폭발(BFS 알고리즘), 중력 낙하, 연쇄(Chain Combo) 계산
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    // 게임 보드 규격 (가로 6칸 x 세로 12칸)
    companion object {
        const val COLS = 6
        const val ROWS = 12
        const val SPAWN_COL = 2
        const val SPAWN_ROW = 0

        // 뿌요 색상 정의
        const val COLOR_EMPTY = 0
        const val COLOR_RED = 1
        const val COLOR_BLUE = 2
        const val COLOR_GREEN = 3
        const val COLOR_YELLOW = 4
    }

    // 게임 상태 리스너 인터페이스 (점수, 연쇄, 게임오버 알림)
    interface GameListener {
        fun onScoreChanged(score: Int, chain: Int)
        fun onNextPuyoChanged(mainColor: Int, subColor: Int)
        fun onGameOver(finalScore: Int)
    }

    var gameListener: GameListener? = null

    // 스레드 및 루프 제어
    private var gameThread: Thread? = null
    @Volatile
    private var isRunning = false
    private var isPaused = false

    // 보드 데이터 [row][col]
    private val board = Array(ROWS) { IntArray(COLS) { COLOR_EMPTY } }

    // 현재 조작 중인 뿌요 쌍
    private var activePivotCol = SPAWN_COL
    private var activePivotRow = SPAWN_ROW
    private var activeMainColor = COLOR_RED
    private var activeSubColor = COLOR_BLUE
    // 회전 방향: 0 = 위(Top), 1 = 오른쪽(Right), 2 = 아래(Bottom), 3 = 왼쪽(Left)
    private var activeRotation = 0

    // 다음 나올 뿌요 쌍
    private var nextMainColor = COLOR_RED
    private var nextSubColor = COLOR_BLUE

    // 게임 제어 변수
    private var isControlledActive = false
    private var isResolvingChains = false
    private var isGameOver = false

    // 점수 및 연쇄 상태
    private var score = 0
    private var currentChain = 0
    private var maxChain = 0

    // 타이머 및 속도 (밀리초)
    private var dropInterval = 800L
    private var lastDropTime = 0L

    // 페인트 객체들
    private val puyoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#1E293B")
    }
    private val eyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val eyePupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(130, 255, 255, 255)
    }
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0F172A")
    }
    private val gridBgPaint = Paint().apply {
        color = Color.parseColor("#1E293B")
    }
    private val gridLinePaint = Paint().apply {
        color = Color.parseColor("#334155")
        strokeWidth = 1.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val chainBannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B")
        textSize = 64f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // 렌더링 영역 좌표
    private var boardLeft = 0f
    private var boardTop = 0f
    private var boardWidth = 0f
    private var boardHeight = 0f
    private var cellSize = 0f

    // 연쇄 배너 표시 시간
    private var chainBannerText = ""
    private var chainBannerEndTime = 0L

    init {
        holder.addCallback(this)
        isFocusable = true
        prepareNextPuyo()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startNewGame()
        isRunning = true
        gameThread = Thread(this, "PuyoGameThread").apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        calculateLayout(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        var retry = true
        while (retry) {
            try {
                gameThread?.join()
                retry = false
            } catch (e: InterruptedException) {
                // 재시도
            }
        }
        gameThread = null
    }

    private fun calculateLayout(width: Int, height: Int) {
        // 보드는 화면 중앙 상단에 가로 6, 세로 12 비율에 맞게 배치
        val maxCellW = (width * 0.90f) / COLS
        val maxCellH = (height * 0.85f) / ROWS
        cellSize = minOf(maxCellW, maxCellH)

        boardWidth = cellSize * COLS
        boardHeight = cellSize * ROWS
        boardLeft = (width - boardWidth) / 2f
        boardTop = (height - boardHeight) / 2f - 20f
        if (boardTop < 40f) boardTop = 40f
    }

    // ===== 게임 메인 루프 =====
    override fun run() {
        var lastTime = System.currentTimeMillis()

        while (isRunning) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastTime
            lastTime = now

            if (!isPaused && !isGameOver) {
                updateGame(now)
            }

            // 그리기
            drawGame()

            // 60FPS 프레임 유지
            val frameTime = System.currentTimeMillis() - now
            val sleepTime = 16L - frameTime
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    // ===== 게임 로직 업데이트 =====
    private fun updateGame(now: Long) {
        // 연쇄 해결 중에는 일반 드롭 중지
        if (isResolvingChains) return

        if (!isControlledActive) {
            spawnPuyo()
            return
        }

        // 자연 낙하 타이머
        if (now - lastDropTime >= dropInterval) {
            lastDropTime = now
            if (!stepSoftDrop()) {
                // 바닥 또는 다른 뿌요에 착지
                lockActivePuyo()
            }
        }
    }

    // 새 뿌요 소환
    private fun spawnPuyo() {
        activePivotCol = SPAWN_COL
        activePivotRow = 0
        activeRotation = 0 // 서브 뿌요는 위쪽에 위치

        activeMainColor = nextMainColor
        activeSubColor = nextSubColor
        prepareNextPuyo()

        // 소환 위치에 이미 뿌요가 있는 경우 게임 오버
        if (board[0][SPAWN_COL] != COLOR_EMPTY) {
            triggerGameOver()
            return
        }

        isControlledActive = true
        lastDropTime = System.currentTimeMillis()
    }

    private fun prepareNextPuyo() {
        val colors = intArrayOf(COLOR_RED, COLOR_BLUE, COLOR_GREEN, COLOR_YELLOW)
        nextMainColor = colors[Random.nextInt(colors.size)]
        nextSubColor = colors[Random.nextInt(colors.size)]
        post {
            gameListener?.onNextPuyoChanged(nextMainColor, nextSubColor)
        }
    }

    // 활성 뿌요의 서브 뿌요 위치 계산
    private fun getSubPuyoPos(col: Int, row: Int, rot: Int): Pair<Int, Int> {
        return when (rot) {
            0 -> Pair(col, row - 1) // 위
            1 -> Pair(col + 1, row) // 오른쪽
            2 -> Pair(col, row + 1) // 아래
            3 -> Pair(col - 1, row) // 왼쪽
            else -> Pair(col, row - 1)
        }
    }

    // 특정 셀이 유효하고 비어있는지 확인
    private fun isCellFree(c: Int, r: Int): Boolean {
        if (c !in 0 until COLS) return false
        if (r !in 0 until ROWS) {
            // 소환 시 위쪽(r = -1)은 허용
            return r < 0
        }
        return board[r][c] == COLOR_EMPTY
    }

    // 현재 위치 및 회전이 유효한지 검증
    private fun canPlace(c: Int, r: Int, rot: Int): Boolean {
        // 메인 뿌요
        if (c !in 0 until COLS || r !in 0 until ROWS) return false
        if (board[r][c] != COLOR_EMPTY) return false

        // 서브 뿌요
        val (sc, sr) = getSubPuyoPos(c, r, rot)
        if (sc !in 0 until COLS) return false
        if (sr in 0 until ROWS && board[sr][sc] != COLOR_EMPTY) return false
        return sr < ROWS
    }

    // ===== 이동 및 회전 조작 함수 (외부/버튼 호출용) =====

    fun moveLeft(): Boolean {
        if (!isControlledActive || isResolvingChains || isGameOver) return false
        if (canPlace(activePivotCol - 1, activePivotRow, activeRotation)) {
            activePivotCol--
            return true
        }
        return false
    }

    fun moveRight(): Boolean {
        if (!isControlledActive || isResolvingChains || isGameOver) return false
        if (canPlace(activePivotCol + 1, activePivotRow, activeRotation)) {
            activePivotCol++
            return true
        }
        return false
    }

    fun stepSoftDrop(): Boolean {
        if (!isControlledActive || isResolvingChains || isGameOver) return false
        if (canPlace(activePivotCol, activePivotRow + 1, activeRotation)) {
            activePivotRow++
            return true
        }
        return false
    }

    fun hardDrop() {
        if (!isControlledActive || isResolvingChains || isGameOver) return
        while (canPlace(activePivotCol, activePivotRow + 1, activeRotation)) {
            activePivotRow++
            score += 1
        }
        lockActivePuyo()
    }

    fun rotateClockwise(): Boolean {
        if (!isControlledActive || isResolvingChains || isGameOver) return false
        val newRot = (activeRotation + 1) % 4
        // 1. 직접 회전 가능 여부
        if (canPlace(activePivotCol, activePivotRow, newRot)) {
            activeRotation = newRot
            return true
        }
        // 2. 벽 차기 (Wall Kick) - 좌우로 1칸 밀어서 회전 시도
        if (canPlace(activePivotCol - 1, activePivotRow, newRot)) {
            activePivotCol--
            activeRotation = newRot
            return true
        }
        if (canPlace(activePivotCol + 1, activePivotRow, newRot)) {
            activePivotCol++
            activeRotation = newRot
            return true
        }
        return false
    }

    fun rotateCounterClockwise(): Boolean {
        if (!isControlledActive || isResolvingChains || isGameOver) return false
        val newRot = (activeRotation + 3) % 4
        if (canPlace(activePivotCol, activePivotRow, newRot)) {
            activeRotation = newRot
            return true
        }
        if (canPlace(activePivotCol + 1, activePivotRow, newRot)) {
            activePivotCol++
            activeRotation = newRot
            return true
        }
        if (canPlace(activePivotCol - 1, activePivotRow, newRot)) {
            activePivotCol--
            activeRotation = newRot
            return true
        }
        return false
    }

    // 조작 중인 뿌요를 보드에 고정
    private fun lockActivePuyo() {
        isControlledActive = false

        // 메인 뿌요 보드 기록
        if (activePivotRow in 0 until ROWS && activePivotCol in 0 until COLS) {
            board[activePivotRow][activePivotCol] = activeMainColor
        }

        // 서브 뿌요 보드 기록
        val (sc, sr) = getSubPuyoPos(activePivotCol, activePivotRow, activeRotation)
        if (sr in 0 until ROWS && sc in 0 until COLS) {
            board[sr][sc] = activeSubColor
        }

        // 착지 후 공중 뿌요 중력 적용 및 연쇄 시작
        currentChain = 0
        isResolvingChains = true

        // 백그라운드 스레드에서 연쇄 애니메이션 단계별 진행
        Thread {
            resolveCascadeAndChains()
        }.start()
    }

    // ===== 중력 낙하 및 연쇄 폭발 알고리즘 (BFS) =====
    private fun resolveCascadeAndChains() {
        // 1. 초기 낙하
        applyGravity()
        Thread.sleep(120)

        var hasMatches = true
        while (hasMatches) {
            val matchedGroups = findMatchingGroups()
            if (matchedGroups.isNotEmpty()) {
                currentChain++
                if (currentChain > maxChain) maxChain = currentChain

                // 폭발할 뿌요 수 카운트
                var totalPopped = 0
                for (group in matchedGroups) {
                    totalPopped += group.size
                    for ((r, c) in group) {
                        board[r][c] = COLOR_EMPTY
                    }
                }

                // 점수 계산 (뿌요뿌요 표준 가중치 반영)
                val chainBonus = when (currentChain) {
                    1 -> 0
                    2 -> 8
                    3 -> 16
                    4 -> 32
                    5 -> 64
                    6 -> 96
                    7 -> 128
                    else -> 160
                }
                val gainedScore = (totalPopped * 10) * maxOf(1, chainBonus)
                score += gainedScore

                // 연쇄 배너 설정
                chainBannerText = "${currentChain}연쇄!! (+${gainedScore})"
                chainBannerEndTime = System.currentTimeMillis() + 1500L

                post {
                    gameListener?.onScoreChanged(score, currentChain)
                }

                // 폭발 애니메이션 대기
                Thread.sleep(250)

                // 2. 남은 뿌요 중력 낙하
                applyGravity()
                Thread.sleep(180)
            } else {
                hasMatches = false
            }
        }

        isResolvingChains = false

        // 상단 스폰 지점에 쌓였는지 확인
        if (board[0][SPAWN_COL] != COLOR_EMPTY) {
            triggerGameOver()
        }
    }

    // 중력 적용: 각 열마다 바닥으로 뿌요를 차곡차곡 내림
    private fun applyGravity(): Boolean {
        var moved = false
        for (c in 0 until COLS) {
            var writeRow = ROWS - 1
            for (r in ROWS - 1 downTo 0) {
                if (board[r][c] != COLOR_EMPTY) {
                    if (r != writeRow) {
                        board[writeRow][c] = board[r][c]
                        board[r][c] = COLOR_EMPTY
                        moved = true
                    }
                    writeRow--
                }
            }
        }
        return moved
    }

    // BFS(너비 우선 탐색)로 상하좌우 4개 이상 연결된 동일 색상 그룹 찾기
    private fun findMatchingGroups(): List<List<Pair<Int, Int>>> {
        val visited = Array(ROWS) { BooleanArray(COLS) { false } }
        val matchingGroups = mutableListOf<List<Pair<Int, Int>>>()

        val dr = intArrayOf(-1, 1, 0, 0)
        val dc = intArrayOf(0, 0, -1, 1)

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val color = board[r][c]
                if (color != COLOR_EMPTY && !visited[r][c]) {
                    // 새 BFS 시작
                    val currentGroup = mutableListOf<Pair<Int, Int>>()
                    val queue: Queue<Pair<Int, Int>> = LinkedList()

                    visited[r][c] = true
                    queue.add(Pair(r, c))
                    currentGroup.add(Pair(r, c))

                    while (queue.isNotEmpty()) {
                        val (cr, cc) = queue.poll()
                        for (i in 0 until 4) {
                            val nr = cr + dr[i]
                            val nc = cc + dc[i]

                            if (nr in 0 until ROWS && nc in 0 until COLS) {
                                if (!visited[nr][nc] && board[nr][nc] == color) {
                                    visited[nr][nc] = true
                                    queue.add(Pair(nr, nc))
                                    currentGroup.add(Pair(nr, nc))
                                }
                            }
                        }
                    }

                    // 4개 이상 연결 시 폭발 대상 등록
                    if (currentGroup.size >= 4) {
                        matchingGroups.add(currentGroup)
                    }
                }
            }
        }
        return matchingGroups
    }

    private fun triggerGameOver() {
        isGameOver = true
        isControlledActive = false
        post {
            gameListener?.onGameOver(score)
        }
    }

    fun startNewGame() {
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                board[r][c] = COLOR_EMPTY
            }
        }
        score = 0
        currentChain = 0
        maxChain = 0
        isGameOver = false
        isControlledActive = false
        isResolvingChains = false
        chainBannerText = ""
        prepareNextPuyo()
        post {
            gameListener?.onScoreChanged(score, 0)
        }
    }

    fun pauseGame() { isPaused = true }
    fun resumeGame() { isPaused = false }

    // ===== 렌더링 파트 =====
    private fun drawGame() {
        val canvas: Canvas = holder.lockCanvas() ?: return
        try {
            // 전체 배경
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // 보드 그리드 배경 및 테두리
            val boardRect = RectF(boardLeft, boardTop, boardLeft + boardWidth, boardTop + boardHeight)
            canvas.drawRoundRect(boardRect, 16f, 16f, gridBgPaint)

            // 내부 그리드 격자선
            for (c in 1 until COLS) {
                val x = boardLeft + c * cellSize
                canvas.drawLine(x, boardTop, x, boardTop + boardHeight, gridLinePaint)
            }
            for (r in 1 until ROWS) {
                val y = boardTop + r * cellSize
                canvas.drawLine(boardLeft, y, boardLeft + boardWidth, y, gridLinePaint)
            }

            // 고정된 보드 뿌요 그리기
            for (r in 0 until ROWS) {
                for (c in 0 until COLS) {
                    val color = board[r][c]
                    if (color != COLOR_EMPTY) {
                        drawPuyo(canvas, c, r, color)
                    }
                }
            }

            // 활성 조작 뿌요 그리기
            if (isControlledActive) {
                // 메인 뿌요
                drawPuyo(canvas, activePivotCol, activePivotRow, activeMainColor)

                // 서브 뿌요
                val (sc, sr) = getSubPuyoPos(activePivotCol, activePivotRow, activeRotation)
                if (sr >= 0) {
                    drawPuyo(canvas, sc, sr, activeSubColor)
                }
            }

            // 연쇄 배너 출력
            val now = System.currentTimeMillis()
            if (chainBannerText.isNotEmpty() && now < chainBannerEndTime) {
                canvas.drawText(chainBannerText, width / 2f, boardTop + boardHeight / 2f, chainBannerPaint)
            }

            // 게임 오버 오버레이
            if (isGameOver) {
                val overlayPaint = Paint().apply {
                    color = Color.argb(190, 0, 0, 0)
                }
                canvas.drawRect(boardRect, overlayPaint)
                textPaint.color = Color.parseColor("#EF4444")
                textPaint.textSize = 56f
                canvas.drawText("GAME OVER", width / 2f, boardTop + boardHeight / 2f - 30f, textPaint)

                textPaint.color = Color.WHITE
                textPaint.textSize = 36f
                canvas.drawText("최종 점수: ${score}", width / 2f, boardTop + boardHeight / 2f + 30f, textPaint)
                canvas.drawText("재시작 버튼을 터치하세요", width / 2f, boardTop + boardHeight / 2f + 90f, textPaint)
            }

        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    // 귀여운 젤리 뿌요 렌더링 (동그란 눈동자 + 반사광)
    private fun drawPuyo(canvas: Canvas, col: Int, row: Int, colorType: Int) {
        val cx = boardLeft + col * cellSize + cellSize / 2f
        val cy = boardTop + row * cellSize + cellSize / 2f
        val radius = cellSize * 0.44f

        // 뿌요 기본 색상 세팅
        puyoPaint.color = when (colorType) {
            COLOR_RED -> Color.parseColor("#EF4444")    // 활기찬 빨강
            COLOR_BLUE -> Color.parseColor("#3B82F6")   // 맑은 파랑
            COLOR_GREEN -> Color.parseColor("#10B981")  // 산뜻한 초록
            COLOR_YELLOW -> Color.parseColor("#F59E0B") // 따뜻한 노랑
            else -> Color.TRANSPARENT
        }

        // 1. 뿌요 몸체
        canvas.drawCircle(cx, cy, radius, puyoPaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)

        // 2. 광택(하이라이트) 효과
        val shineX = cx - radius * 0.35f
        val shineY = cy - radius * 0.35f
        canvas.drawCircle(shineX, shineY, radius * 0.22f, shinePaint)

        // 3. 뿌요뿌요 시그니처 흰 눈과 까만 눈동자
        val eyeRadius = radius * 0.26f
        val pupilRadius = eyeRadius * 0.55f

        val leftEyeX = cx - radius * 0.32f
        val rightEyeX = cx + radius * 0.32f
        val eyeY = cy - radius * 0.05f

        // 눈 흰자위
        canvas.drawCircle(leftEyeX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawCircle(rightEyeX, eyeY, eyeRadius, eyeWhitePaint)
        canvas.drawCircle(leftEyeX, eyeY, eyeRadius, strokePaint)
        canvas.drawCircle(rightEyeX, eyeY, eyeRadius, strokePaint)

        // 눈동자
        canvas.drawCircle(leftEyeX + eyeRadius * 0.2f, eyeY + eyeRadius * 0.1f, pupilRadius, eyePupilPaint)
        canvas.drawCircle(rightEyeX + eyeRadius * 0.2f, eyeY + eyeRadius * 0.1f, pupilRadius, eyePupilPaint)
    }

    // 터치 스와이프 조작 보조
    private var touchStartX = 0f
    private var touchStartY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY

                if (Math.abs(dx) > Math.abs(dy)) {
                    if (dx > 60) moveRight()
                    else if (dx < -60) moveLeft()
                } else {
                    if (dy > 80) hardDrop()
                    else if (dy < -40) rotateClockwise()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

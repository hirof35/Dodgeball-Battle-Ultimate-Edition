package dodgeballGame;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public class DodgeballGame extends JFrame {
    public DodgeballGame() {
        setTitle("Dodgeball Battle: Ultimate Edition");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false); // サイズ変更不可にしてレイアウト崩れを防ぐ
        add(new GamePanel());
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    public static void main(String[] args) {
        // SwingのUIスレッドで実行
        SwingUtilities.invokeLater(DodgeballGame::new);
    }
}

class GamePanel extends JPanel implements ActionListener, KeyListener {
    // --- 定数 ---
    private static final int WIDTH = 500;
    private static final int HEIGHT = 500;
    private static final int PLAYER_SIZE = 50;
    private static final String SCORE_FILE = "highscore.dat";

    // --- 状態管理 ---
    private enum State { TITLE, PLAYING, GAMEOVER }
    private State currentState = State.TITLE;

    // --- ゲームオブジェクト ---
    private int playerX = 225, playerY = 400;
    private double enemyX = 200, enemyDX = 4.0;
    private int enemyHealth, enemyMaxHealth = 15;
    private int score = 0, highScore = 0;

    // --- パラメータ ---
    private double fireRate;
    private int enemyBallSpeed;
    private int frameCount;
    private int shakeDuration = 0, shakeIntensity = 0;

    private final ArrayList<Ball> enemyBalls = new ArrayList<>();
    private final ArrayList<Ball> playerBalls = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final Timer timer;
    private final Random random = new Random();

    public GamePanel() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(new Color(10, 15, 25));
        setFocusable(true);
        addKeyListener(this);
        
        loadHighScore();
        SoundManager.load("bgm", "sounds/bgm.wav");
        SoundManager.load("shot", "sounds/shot.wav");
        SoundManager.load("hit", "sounds/hit.wav");
        SoundManager.load("lose", "sounds/lose.wav");
        
        // 60FPS (1000ms / 60 = 16.6ms)
        timer = new Timer(16, this);
        timer.start();

        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) { 
                if (currentState == State.PLAYING) {
                    // プレイヤーの中心がマウスに来るように調整
                    playerX = Math.max(0, Math.min(WIDTH - PLAYER_SIZE, e.getX() - PLAYER_SIZE / 2));
                }
            }
        });
    }

    private void resetGame() {
        score = 0;
        enemyHealth = enemyMaxHealth;
        fireRate = 0.02;
        enemyBallSpeed = 5;
        frameCount = 0;
        enemyBalls.clear();
        playerBalls.clear();
        particles.clear();
        currentState = State.PLAYING;
        SoundManager.playBGM("bgm");
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (currentState == State.PLAYING) {
            updateGame();
        }
        repaint();
    }

    private void updateGame() {
        frameCount++;
        
        // 難易度上昇
        if (frameCount % 400 == 0) {
            fireRate += 0.01;
            enemyDX *= 1.1;
            enemyBallSpeed++;
        }

        // 敵の移動
        enemyX += enemyDX;
        if (enemyX < 0 || enemyX > WIDTH - 60) enemyDX *= -1;

        // 敵の攻撃
        if (random.nextDouble() < fireRate) {
            enemyBalls.add(new Ball(enemyX + 25, 50, 0, enemyBallSpeed));
        }

        updateProjectiles();
        
        // パーティクルの更新
        particles.removeIf(p -> {
            p.update();
            return p.isDead();
        });
    }

    private void updateProjectiles() {
        // 敵の弾の移動と判定
        Rectangle pRect = new Rectangle(playerX, playerY, PLAYER_SIZE, PLAYER_SIZE);
        Iterator<Ball> itE = enemyBalls.iterator();
        while (itE.hasNext()) {
            Ball b = itE.next();
            b.y += b.vy;
            if (b.y > HEIGHT) {
                itE.remove();
                score += 10;
            } else if (pRect.intersects(b.getBounds(10))) {
                triggerGameOver();
                break;
            }
        }

        // プレイヤーの弾の移動と判定
        Rectangle eRect = new Rectangle((int)enemyX, 20, 60, 30);
        Iterator<Ball> itP = playerBalls.iterator();
        while (itP.hasNext()) {
            Ball b = itP.next();
            b.x += b.vx; b.y += b.vy;
            if (b.y < 0 || b.x < 0 || b.x > WIDTH) {
                itP.remove();
            } else if (eRect.intersects(b.getBounds(8))) {
                itP.remove();
                enemyHealth--;
                score += 50;
                SoundManager.playSE("hit");
                createExplosion((int)b.x, (int)b.y);
                if (enemyHealth <= 0) triggerVictory();
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        // アンチエイリアスを有効化
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (shakeDuration > 0) {
            g2d.translate(random.nextInt(shakeIntensity) - shakeIntensity/2, random.nextInt(shakeIntensity) - shakeIntensity/2);
            shakeDuration--;
        }

        switch (currentState) {
            case TITLE -> drawTitle(g2d);
            case PLAYING -> drawAction(g2d);
            case GAMEOVER -> drawGameOverScreen(g2d);
        }
    }

    private void drawTitle(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.BOLD, 40));
        drawCenteredString(g, "DODGE BATTLE", 200);
        
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        g.setColor(Color.YELLOW);
        drawCenteredString(g, "HI-SCORE: " + highScore, 250);
        
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            g.setColor(Color.WHITE);
            drawCenteredString(g, "PRESS ENTER TO START", 350);
        }
    }

    private void drawAction(Graphics2D g) {
        // 敵
        g.setColor(Color.RED);
        g.fillRoundRect((int)enemyX, 20, 60, 30, 10, 10);
        
        // プレイヤー
        int level = getPlayerLevel();
        g.setColor(level == 3 ? Color.MAGENTA : (level == 2 ? Color.ORANGE : Color.CYAN));
        g.fillRect(playerX, playerY, PLAYER_SIZE, PLAYER_SIZE);

        // 弾
        g.setColor(Color.WHITE);
        for (Ball b : enemyBalls) g.fillOval((int)b.x, (int)b.y, 10, 10);
        g.setColor(Color.YELLOW);
        for (Ball b : playerBalls) g.fillOval((int)b.x, (int)b.y, 8, 8);
        
        // パーティクル
        for (Particle p : particles) p.draw(g);

        // UI
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 14));
        g.drawString("SCORE: " + score, 15, 25);
        g.drawString("HP: " + enemyHealth, (int)enemyX + 10, 15);
    }

    private void drawGameOverScreen(Graphics2D g) {
        boolean victory = enemyHealth <= 0;
        g.setColor(victory ? Color.GREEN : Color.RED);
        g.setFont(new Font("Arial", Font.BOLD, 50));
        drawCenteredString(g, victory ? "VICTORY!" : "GAME OVER", 230);
        
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.PLAIN, 20));
        drawCenteredString(g, "Final Score: " + score, 280);
        drawCenteredString(g, "Press Enter to Title", 350);
    }

    // --- ユーティリティ ---
    private void drawCenteredString(Graphics2D g, String text, int y) {
        FontMetrics fm = g.getFontMetrics();
        int x = (WIDTH - fm.stringWidth(text)) / 2;
        g.drawString(text, x, y);
    }

    private int getPlayerLevel() {
        if (enemyHealth <= 4) return 3;
        if (enemyHealth <= 9) return 2;
        return 1;
    }

    private void triggerGameOver() {
        currentState = State.GAMEOVER;
        SoundManager.stopBGM();
        SoundManager.playSE("lose");
        shakeDuration = 30; shakeIntensity = 15;
        checkHighScore();
    }

    private void triggerVictory() {
        currentState = State.GAMEOVER;
        SoundManager.stopBGM();
        checkHighScore();
    }

    private void createExplosion(int x, int y) {
        for (int i = 0; i < 15; i++) particles.add(new Particle(x, y, Color.ORANGE));
    }

    private void loadHighScore() {
        File file = new File(SCORE_FILE);
        if (!file.exists()) return;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            highScore = dis.readInt();
        } catch (IOException ignored) {}
    }

    private void checkHighScore() {
        if (score > highScore) {
            highScore = score;
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(SCORE_FILE))) {
                dos.writeInt(highScore);
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int key = e.getKeyCode();
        if (key == KeyEvent.VK_ENTER) {
            if (currentState != State.PLAYING) resetGame();
        }
        if (key == KeyEvent.VK_SPACE && currentState == State.PLAYING) {
            firePlayerShot();
        }
    }
    public void keyTyped(KeyEvent e) {}
    public void keyReleased(KeyEvent e) {}

    private void firePlayerShot() {
        int level = getPlayerLevel();
        SoundManager.playSE("shot");
        int bulletY = playerY - 10;
        switch (level) {
            case 1 -> playerBalls.add(new Ball(playerX + 21, bulletY, 0, -12));
            case 2 -> {
                playerBalls.add(new Ball(playerX + 5, bulletY, 0, -12));
                playerBalls.add(new Ball(playerX + 37, bulletY, 0, -12));
            }
            default -> {
                playerBalls.add(new Ball(playerX + 21, bulletY, 0, -12));
                playerBalls.add(new Ball(playerX + 21, bulletY, -3, -11));
                playerBalls.add(new Ball(playerX + 21, bulletY, 3, -11));
            }
        }
    }
}

class Ball {
    double x, y, vx, vy;
    Ball(double x, double y, double vx, double vy) {
        this.x = x; this.y = y; this.vx = vx; this.vy = vy;
    }
    // 当たり判定用の矩形を取得
    Rectangle getBounds(int size) {
        return new Rectangle((int)x, (int)y, size, size);
    }
}

class Particle {
    double x, y, vx, vy;
    int life, maxLife;
    Color color;

    Particle(int x, int y, Color c) {
        this.x = x; this.y = y; this.color = c;
        this.vx = (Math.random() - 0.5) * 6;
        this.vy = (Math.random() - 0.5) * 6;
        this.maxLife = new Random().nextInt(15) + 15;
        this.life = maxLife;
    }

    void update() {
        x += vx; y += vy;
        vx *= 0.96; vy *= 0.96;
        life--;
    }

    void draw(Graphics2D g) {
        float alpha = (float) life / maxLife;
        g.setColor(new Color(color.getRed()/255f, color.getGreen()/255f, color.getBlue()/255f, alpha));
        g.fillOval((int)x, (int)y, 4, 4);
    }

    boolean isDead() { return life <= 0; }
}

class SoundManager {
    private static final ConcurrentHashMap<String, Clip> clips = new ConcurrentHashMap<>();
    private static Clip bgmClip = null;

    public static void load(String name, String path) {
        try {
            File file = new File(path);
            if (!file.exists()) return;
            AudioInputStream ais = AudioSystem.getAudioInputStream(file);
            Clip c = AudioSystem.getClip();
            c.open(ais);
            clips.put(name, c);
        } catch (Exception e) {
            System.err.println("Error loading sound: " + path);
        }
    }

    public static void playSE(String name) {
        Clip c = clips.get(name);
        if (c != null) {
            c.stop();
            c.setFramePosition(0);
            c.start();
        }
    }

    public static void playBGM(String name) {
        stopBGM();
        bgmClip = clips.get(name);
        if (bgmClip != null) {
            bgmClip.setFramePosition(0);
            bgmClip.loop(Clip.LOOP_CONTINUOUSLY);
        }
    }

    public static void stopBGM() {
        if (bgmClip != null && bgmClip.isRunning()) {
            bgmClip.stop();
        }
    }
}

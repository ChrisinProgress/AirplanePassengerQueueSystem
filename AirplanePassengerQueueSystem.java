import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.sql.*;
import java.util.*;
import java.util.Queue;

public class AirplanePassengerQueueSystem extends JFrame {
    /* ============== SQLite helper ============== */
    private static final class Db {
        private static final String URL = "jdbc:sqlite:airplane.db";
        static { init(); }
        static void ensure() { init(); }

        private static void init() {
            try (Connection c = DriverManager.getConnection(URL);
                 Statement st = c.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS passengers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        plane TEXT NOT NULL,
                        seat TEXT,
                        isreserved TEXT NOT NULL,  -- "Yes" or "No"
                        isboarded  TEXT NOT NULL   -- "Yes" or "No"
                    )
                """);
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }

        static void savePassenger(String name, String type, String plane,
                                  String seat, boolean reserved, boolean boarded) {
            String sql = "INSERT INTO passengers(name,type,plane,seat,isreserved,isboarded) VALUES (?,?,?,?,?,?)";
            try (Connection c = DriverManager.getConnection(URL);
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, type);
                ps.setString(3, plane);
                ps.setString(4, seat);
                ps.setString(5, reserved ? "Yes" : "No");
                ps.setString(6, boarded ? "Yes" : "No");
                ps.executeUpdate();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }

        static void updateBoarded(String plane, String name, String seat, boolean boarded) {
            String sql = "UPDATE passengers SET isboarded=? WHERE plane=? AND name=? AND seat=?";
            try (Connection c = DriverManager.getConnection(URL);
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, boarded ? "Yes" : "No");
                ps.setString(2, plane);
                ps.setString(3, name);
                ps.setString(4, seat);
                ps.executeUpdate();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }

        static void updateSeat(String plane, String name, String newSeat,
                               boolean reserved, boolean boarded) {
            String sql = "UPDATE passengers SET seat=?, isreserved=?, isboarded=? WHERE plane=? AND name=?";
            try (Connection c = DriverManager.getConnection(URL);
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, newSeat);
                ps.setString(2, reserved ? "Yes" : "No");
                ps.setString(3, boarded ? "Yes" : "No");
                ps.setString(4, plane);
                ps.setString(5, name);
                ps.executeUpdate();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }

        static void clearPlane(String plane) {
            try (Connection c = DriverManager.getConnection(URL);
                 PreparedStatement ps = c.prepareStatement("DELETE FROM passengers WHERE plane=?")) {
                ps.setString(1, plane);
                ps.executeUpdate();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }

        static java.util.List<Passenger> loadAll() {
            ensure();
            java.util.List<Passenger> out = new ArrayList<>();
            String sql = "SELECT name,type,plane,seat,isreserved,isboarded FROM passengers ORDER BY id ASC";
            try (Connection c = DriverManager.getConnection(URL);
                 PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Passenger p = new Passenger();
                    p.name = rs.getString("name");
                    p.type = rs.getString("type");
                    p.seatCode = rs.getString("seat");
                    p.reservedLocked = "Yes".equalsIgnoreCase(rs.getString("isreserved"));
                    p.boarded = "Yes".equalsIgnoreCase(rs.getString("isboarded"));
                    p._plane = Plane.valueOf(rs.getString("plane")); // temp holder during load
                    out.add(p);
                }
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
            return out;
        }
    }

    /* ============== Domain model ============== */
    private enum Plane { A, B, C }
    private enum SeatStatus { UNOCCUPIED, RESERVED, OCCUPIED }

    private static class Passenger {
        String name;
        String type;          // "Business" / "Economy"
        String seatCode;      // e.g., "1A"
        boolean boarded;
        boolean reservedLocked;
        Plane _plane;         // used only when loading from DB
        @Override
        public String toString() {
            return name + " (" + type +
                    (seatCode != null ? ", Seat " + seatCode : ", No seat") +
                    (boarded ? ", BOARDED" : ", UNBOARDED") + ")";
        }
    }

    // Colors
    private static final Color BG_DARK   = Color.decode("#061122");
    private static final Color YELLOW    = Color.decode("#FFFF89");
    private static final Color OCCUPIED_COLOR   = new Color(255, 190, 190);
    private static final Color RESERVED_COLOR   = new Color(255, 255, 180);
    private static final Color UNOCCUPIED_COLOR = new Color(190, 255, 190);
    private static final Color SEAT_BORDER      = new Color(230, 230, 230);
    private static final Color VACANT_BOARDING  = new Color(190, 255, 190);
    private static final Color UNBOARDED_COLOR  = new Color(190, 220, 255);
    private static final Color BOARDED_COLOR    = new Color(255, 190, 190);
    private static final Color GLOW_YELLOW_CENTER = new Color(255, 220, 120, 180);
    private static final Color GLOW_YELLOW_EDGE   = new Color(255, 220, 120, 0);
    private static final Color GLOW_BLUE_CENTER   = new Color(60, 130, 255, 180);
    private static final Color GLOW_BLUE_EDGE     = new Color(60, 130, 255, 0);

    // Per-plane data
    private final Map<Plane, Queue<Passenger>> planeQueues        = new EnumMap<>(Plane.class);
    private final Map<Plane, Map<String, SeatStatus>> seatMaps    = new EnumMap<>(Plane.class);
    private final Map<Plane, Map<String, Passenger>> seatOwners   = new EnumMap<>(Plane.class);
    private final Map<Plane, java.util.List<Passenger>> allPax    = new EnumMap<>(Plane.class);

    private final Map<String, JButton> seatButtons = new HashMap<>();
    private Plane lockedPlane = null;
    private Passenger currentPassenger = null;
    private int currentPassengerIndex = 0;

    // Cards
    private static final String CARD_HOME    = "HOME";
    private static final String CARD_DISPLAY = "DISPLAY";
    private static final String CARD_SEATING = "SEATING";
    private static final String CARD_BOARD   = "BOARD";

    private final JPanel cardPanel = new JPanel(new CardLayout());
    private final ImageIcon airplaneIcon = createScaledAirplaneIcon();

    /* ==== Home UI ==== */
    private JTextField txtName;
    private JRadioButton rbEconomy, rbBusiness;
    private JRadioButton rbPlaneAHome, rbPlaneBHome, rbPlaneCHome;
    private JRadioButton rbReserveYes, rbReserveNo;
    private JButton btnStartPlane, btnAddPassenger;
    private JLabel lblHomeStatus;

    /* ==== Queue UI ==== */
    private JTextArea txtQueue;
    private JLabel lblDisplayStatus;
    private JLabel lblPlaneQueue;
    private JRadioButton rbPlaneAQueue, rbPlaneBQueue, rbPlaneCQueue;

    /* ==== Seating UI ==== */
    private JLabel lblPlaneSeating;
    private JLabel lblCurrentPassenger;
    private JTextArea txtQueueSeating;
    private JPanel seatGridPanel;
    private JButton btnPrevPassenger, btnNextPassenger, btnBackHomeSeating;
    private JRadioButton rbPlaneASeat, rbPlaneBSeat, rbPlaneCSeat;

    /* ==== Boarding UI ==== */
    private JPanel boardingGridPanel;
    private JLabel lblPlaneBoard;
    private JRadioButton rbPlaneABoard, rbPlaneBBoard, rbPlaneCBoard;

    public AirplanePassengerQueueSystem() {
        setTitle("Airplane Passenger Queue System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 600);
        setLocationRelativeTo(null);
        setResizable(true);

        UIManager.put("Label.font",       new Font("Segoe UI", Font.PLAIN, 12));
        UIManager.put("Button.font",      new Font("Segoe UI", Font.BOLD, 12));
        UIManager.put("RadioButton.font", new Font("Segoe UI", Font.PLAIN, 12));
        UIManager.put("TextField.font",   new Font("Segoe UI", Font.PLAIN, 12));
        UIManager.put("TextArea.font",    new Font("Segoe UI", Font.PLAIN, 12));

        initModel();

        JPanel bg = new JPanel(new BorderLayout());
        bg.setBackground(BG_DARK);
        bg.add(createNavBar(), BorderLayout.NORTH);
        bg.add(cardPanel, BorderLayout.CENTER);

        cardPanel.add(createHomePanel(),    CARD_HOME);
        cardPanel.add(createQueuePanel(),   CARD_DISPLAY);
        cardPanel.add(createSeatingPanel(), CARD_SEATING);
        cardPanel.add(createBoardingPanel(),CARD_BOARD);

        setContentPane(bg);
        setGlassPane(new GlowPane());
        getGlassPane().setVisible(true);

        loadFromDb();
        refreshAllUi();
    }

    /** Load airplane.png and scale it down */
    private ImageIcon createScaledAirplaneIcon() {
        java.net.URL url = Objects.requireNonNull(
                getClass().getResource("airplane.png"),
                "airplane.png not found on classpath"
        );
        ImageIcon base = new ImageIcon(url);
        int targetW = 44;
        int targetH = 44;
        Image scaled = base.getImage().getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

    /* ===================== Model init ===================== */
    private void initModel() {
        for (Plane p : Plane.values()) {
            planeQueues.put(p, new LinkedList<>());
            seatMaps.put(p, new HashMap<>());
            seatOwners.put(p, new HashMap<>());
            allPax.put(p, new ArrayList<>());

            Map<String, SeatStatus> map = seatMaps.get(p);
            String[] leftCols   = {"A", "B"};
            String[] midCols    = {"C", "D", "E", "F"};
            String[] rightCols  = {"H", "J"};
            for (int row = 1; row <= 15; row++) {
                for (String c : leftCols)  map.put(row + c, SeatStatus.UNOCCUPIED);
                for (String c : midCols)   map.put(row + c, SeatStatus.UNOCCUPIED);
                for (String c : rightCols) map.put(row + c, SeatStatus.UNOCCUPIED);
            }
        }
    }

    /* ===================== Persistence load ===================== */
    private void loadFromDb() {
        Db.ensure();
        java.util.List<Passenger> loaded = Db.loadAll();
        for (Passenger p : loaded) {
            Plane plane = p._plane;
            if (plane == null) continue;
            if (p.seatCode != null && !p.seatCode.isEmpty()) {
                SeatStatus newStatus = p.reservedLocked ? SeatStatus.RESERVED : SeatStatus.OCCUPIED;
                seatMaps.get(plane).put(p.seatCode, newStatus);
                seatOwners.get(plane).put(p.seatCode, p);
            }
            if (!p.boarded) {
                planeQueues.get(plane).add(p);
            }
            allPax.get(plane).add(p);
        }
    }

    private void refreshAllUi() {
        if (lockedPlane == null) lockedPlane = Plane.A;
        syncPlaneSelectors();
        updateCurrentPassenger();
        refreshQueueDisplay();
        refreshSeatingQueue();
        updateSeatGrid();
        refreshBoardingGrid();
    }

    /* ===================== Glow Pane ===================== */
    private class GlowPane extends JComponent {
        GlowPane() { setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            float baseR = Math.min(w, h) * 0.35f;
            float r = Math.max(140f, Math.min(baseR, 320f));
            if (r > 0) {
                RadialGradientPaint yellowPaint = new RadialGradientPaint(
                        new Point.Float(0f, 0f), r,
                        new float[]{0f, 1f},
                        new Color[]{GLOW_YELLOW_CENTER, GLOW_YELLOW_EDGE});
                g2.setPaint(yellowPaint);
                g2.fillRect(0, 0, (int)(r * 2), (int)(r * 2));
                RadialGradientPaint bluePaint = new RadialGradientPaint(
                        new Point.Float(w, h), r,
                        new float[]{0f, 1f},
                        new Color[]{GLOW_BLUE_CENTER, GLOW_BLUE_EDGE});
                g2.setPaint(bluePaint);
                g2.fillRect((int)(w - r * 2), (int)(h - r * 2), (int)(r * 2), (int)(r * 2));
            }
            g2.dispose();
        }
    }

    /* ===================== Navbar ===================== */
    private JPanel createNavBar() {
        JPanel nav = new JPanel();
        nav.setBackground(BG_DARK);
        nav.setLayout(new BoxLayout(nav, BoxLayout.X_AXIS));
        nav.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        JLabel brand = new JLabel("");
        brand.setForeground(Color.LIGHT_GRAY);

        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 0));

        JLabel home = new JLabel("Home");
        home.setForeground(Color.WHITE);
        home.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        home.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                goHomeAndUnlockPlane();
            }
        });

        JLabel seating = navLabel("Seating", CARD_SEATING);
        JLabel queue   = navLabel("Queue",   CARD_DISPLAY);
        JLabel board   = navLabel("Boarding",CARD_BOARD);

        nav.add(brand);
        nav.add(spacer);
        nav.add(home);
        nav.add(Box.createHorizontalStrut(20));
        nav.add(seating);
        nav.add(Box.createHorizontalStrut(20));
        nav.add(queue);
        nav.add(Box.createHorizontalStrut(20));
        nav.add(board);
        return nav;
    }

    private JLabel navLabel(String text, String card) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(Color.WHITE);
        lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lbl.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { showCard(card); }
        });
        return lbl;
    }

    private void showCard(String cardName) {
        CardLayout cl = (CardLayout) cardPanel.getLayout();

        if (!CARD_HOME.equals(cardName) && lockedPlane == null) {
            JOptionPane.showMessageDialog(this,
                    "Please choose a plane and click 'Start Plane' first.",
                    "Plane not selected",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        cl.show(cardPanel, cardName);

        if (CARD_DISPLAY.equals(cardName)) {
            syncPlaneSelectors();
            refreshQueueDisplay();
        }
        if (CARD_SEATING.equals(cardName)) {
            syncPlaneSelectors();
            updateCurrentPassenger();
            updateSeatGrid();
            refreshSeatingQueue();
        }
        if (CARD_BOARD.equals(cardName)) {
            syncPlaneSelectors();
            refreshBoardingGrid();
        }
    }

    private void goHomeAndUnlockPlane() {
        lockedPlane = null;

        if (btnStartPlane != null) btnStartPlane.setEnabled(true);
        if (lblHomeStatus != null) lblHomeStatus.setText(" ");

        if (lblPlaneSeating != null) lblPlaneSeating.setText("Current Plane: (none)");
        if (seatGridPanel != null) { seatGridPanel.removeAll(); seatGridPanel.revalidate(); seatGridPanel.repaint(); }

        if (lblPlaneBoard != null) lblPlaneBoard.setText("Current Plane: (none)");
        if (boardingGridPanel != null) { boardingGridPanel.removeAll(); boardingGridPanel.revalidate(); boardingGridPanel.repaint(); }

        if (lblPlaneQueue != null) lblPlaneQueue.setText("Current Plane: (none)");
        if (txtQueue != null) txtQueue.setText("No plane selected.");
        if (txtQueueSeating != null) txtQueueSeating.setText("No plane selected.");
        if (lblDisplayStatus != null) lblDisplayStatus.setText(" ");

        CardLayout cl = (CardLayout) cardPanel.getLayout();
        cl.show(cardPanel, CARD_HOME);
    }

    /* ===================== Home panel (persistence in addPassenger) ===================== */
    private JPanel createHomePanel() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(BG_DARK);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;

        JPanel topStack = new JPanel();
        topStack.setOpaque(false);
        topStack.setLayout(new BoxLayout(topStack, BoxLayout.Y_AXIS));
        topStack.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel lblIcon = new JLabel(airplaneIcon);
        lblIcon.setAlignmentX(Component.CENTER_ALIGNMENT);
        topStack.add(lblIcon);
        topStack.add(Box.createVerticalStrut(6));

        JLabel lbl1 = new JLabel("Begin Your Journey At");
        lbl1.setForeground(Color.WHITE);
        lbl1.setFont(new Font("Segoe UI", Font.PLAIN, 20));
        lbl1.setAlignmentX(Component.CENTER_ALIGNMENT);
        topStack.add(lbl1);
        topStack.add(Box.createVerticalStrut(4));

        JLabel lblTitle = new JLabel("Airplane Passenger Queue System");
        lblTitle.setForeground(YELLOW);
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 26));
        lblTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        topStack.add(lblTitle);

        root.add(topStack, gbc);

        gbc.gridwidth = 1;
        gbc.gridy++;
        gbc.anchor = GridBagConstraints.EAST;
        JLabel lblName = new JLabel("Passenger Name:");
        lblName.setForeground(Color.WHITE);
        lblName.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        root.add(lblName, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        txtName = new JTextField(20);
        txtName.setPreferredSize(new Dimension(260, 28));
        root.add(txtName, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.anchor = GridBagConstraints.EAST;
        JLabel lblType = new JLabel("Passenger Type:");
        lblType.setForeground(Color.WHITE);
        lblType.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        root.add(lblType, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        JPanel typePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        typePanel.setOpaque(false);
        rbEconomy  = new JRadioButton("Economy", true);
        rbBusiness = new JRadioButton("Business");
        rbEconomy.setBackground(BG_DARK);
        rbBusiness.setBackground(BG_DARK);
        rbEconomy.setForeground(Color.WHITE);
        rbBusiness.setForeground(Color.WHITE);
        ButtonGroup bgType = new ButtonGroup();
        bgType.add(rbEconomy);
        bgType.add(rbBusiness);
        typePanel.add(rbEconomy);
        typePanel.add(rbBusiness);
        root.add(typePanel, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.anchor = GridBagConstraints.EAST;
        JLabel lblPlane = new JLabel("Plane:");
        lblPlane.setForeground(Color.WHITE);
        lblPlane.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        root.add(lblPlane, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        JPanel planePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        planePanel.setOpaque(false);
        rbPlaneAHome = new JRadioButton("Plane A", true);
        rbPlaneBHome = new JRadioButton("Plane B");
        rbPlaneCHome = new JRadioButton("Plane C");
        rbPlaneAHome.setBackground(BG_DARK);
        rbPlaneBHome.setBackground(BG_DARK);
        rbPlaneCHome.setBackground(BG_DARK);
        rbPlaneAHome.setForeground(Color.WHITE);
        rbPlaneBHome.setForeground(Color.WHITE);
        rbPlaneCHome.setForeground(Color.WHITE);
        ButtonGroup bgPlaneHome = new ButtonGroup();
        bgPlaneHome.add(rbPlaneAHome);
        bgPlaneHome.add(rbPlaneBHome);
        bgPlaneHome.add(rbPlaneCHome);
        planePanel.add(rbPlaneAHome);
        planePanel.add(rbPlaneBHome);
        planePanel.add(rbPlaneCHome);
        root.add(planePanel, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.anchor = GridBagConstraints.EAST;
        JLabel lblReserve = new JLabel("Reserve seat?");
        lblReserve.setForeground(Color.WHITE);
        lblReserve.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        root.add(lblReserve, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.WEST;
        JPanel resPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        resPanel.setOpaque(false);
        rbReserveYes = new JRadioButton("Yes");
        rbReserveNo  = new JRadioButton("No", true);
        rbReserveYes.setBackground(BG_DARK);
        rbReserveNo.setBackground(BG_DARK);
        rbReserveYes.setForeground(Color.WHITE);
        rbReserveNo.setForeground(Color.WHITE);
        ButtonGroup bgRes = new ButtonGroup();
        bgRes.add(rbReserveYes);
        bgRes.add(rbReserveNo);
        resPanel.add(rbReserveYes);
        resPanel.add(rbReserveNo);
        root.add(resPanel, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.CENTER;

        btnAddPassenger = createYellowButton("Add Passenger");
        btnAddPassenger.addActionListener(e -> addPassenger());
        root.add(btnAddPassenger, gbc);

        gbc.gridx = 1;
        btnStartPlane = createYellowButton("Start Plane");
        btnStartPlane.addActionListener(e -> {
            if (lockedPlane == null) {
                lockedPlane = getSelectedPlaneHome();
                btnStartPlane.setEnabled(false);
                lblHomeStatus.setText("Plane " + lockedPlane.name() + " locked. Use Seating / Queue / Boarding.");
                syncPlaneSelectors();
            }
            showCard(CARD_SEATING);
        });
        root.add(btnStartPlane, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        lblHomeStatus = new JLabel(" ", SwingConstants.CENTER);
        lblHomeStatus.setForeground(new Color(200, 255, 200));
        lblHomeStatus.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        root.add(lblHomeStatus, gbc);

        return root;
    }

    private Plane getSelectedPlaneHome() {
        if (rbPlaneBHome.isSelected()) return Plane.B;
        if (rbPlaneCHome.isSelected()) return Plane.C;
        return Plane.A;
    }

    private void addPassenger() {
        String name = txtName.getText() != null ? txtName.getText().trim() : "";
        if (name.isEmpty()) {
            lblHomeStatus.setForeground(new Color(255, 200, 200));
            lblHomeStatus.setText("Please enter a passenger name.");
            return;
        }

        Plane plane = getSelectedPlaneHome();
        String type = rbBusiness.isSelected() ? "Business" : "Economy";
        boolean reserveYes = rbReserveYes.isSelected();
        Map<String, SeatStatus> map = seatMaps.get(plane);

        if ("Business".equals(type) && !hasBusinessSeatAvailable(map)) {
            lblHomeStatus.setForeground(new Color(255, 200, 200));
            lblHomeStatus.setText("Business rows 1–2 are full on Plane " + plane.name() + ". Choose another plane.");
            return;
        }

        String seat = autoAssignSeat(map, type);
        if (seat == null) {
            lblHomeStatus.setForeground(new Color(255, 200, 200));
            lblHomeStatus.setText("No available seats for " + type + " on Plane " + plane.name() + ".");
            return;
        }

        Passenger p = new Passenger();
        p.name = name;
        p.type = type;
        p.seatCode = seat;
        p.reservedLocked = reserveYes;
        p.boarded = false;

        seatOwners.get(plane).put(seat, p);
        seatMaps.get(plane).put(seat, reserveYes ? SeatStatus.RESERVED : SeatStatus.OCCUPIED);
        planeQueues.get(plane).add(p);
        allPax.get(plane).add(p);

        Db.savePassenger(p.name, p.type, plane.name(), p.seatCode, p.reservedLocked, p.boarded);

        lblHomeStatus.setForeground(new Color(200, 255, 200));
        lblHomeStatus.setText("Added " + name + " (" + type + ") Seat " + seat + " to Plane " + plane.name() + ".");

        if (lockedPlane == plane) {
            updateSeatGrid();
            if (planeQueues.get(plane).size() == 1) currentPassengerIndex = 0;
            updateCurrentPassenger();
            refreshSeatingQueue();
            refreshBoardingGrid();
        }

        txtName.setText("");
    }

    private boolean hasBusinessSeatAvailable(Map<String, SeatStatus> map) {
        String[] cols = {"A","B","C","D","E","F","H","J"};
        for (int row = 1; row <= 2; row++) {
            for (String c : cols) {
                String code = row + c;
                if (map.get(code) == SeatStatus.UNOCCUPIED) return true;
            }
        }
        return false;
    }

    private String autoAssignSeat(Map<String, SeatStatus> map, String type) {
        String[] cols = {"A","B","C","D","E","F","H","J"};
        int startRow = "Business".equals(type) ? 1 : 3;
        int endRow   = "Business".equals(type) ? 2 : 15;
        for (int r = startRow; r <= endRow; r++) {
            for (String c : cols) {
                String code = r + c;
                if (map.get(code) == SeatStatus.UNOCCUPIED) return code;
            }
        }
        return null;
    }

    /* ===================== Queue panel ===================== */
    private JPanel createQueuePanel() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(BG_DARK);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 10, 5, 10);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;

        JLabel lbl1 = new JLabel("You Are Now In");
        lbl1.setForeground(Color.WHITE);
        lbl1.setFont(new Font("Segoe UI", Font.PLAIN, 20));
        root.add(lbl1, gbc);

        gbc.gridy++;
        JLabel lblTitle = new JLabel("Queue Display");
        lblTitle.setForeground(YELLOW);
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 24));
        root.add(lblTitle, gbc);

        gbc.gridy++;
        lblPlaneQueue = new JLabel("Current Plane: (none)");
        lblPlaneQueue.setForeground(Color.WHITE);
        lblPlaneQueue.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        root.add(lblPlaneQueue, gbc);

        gbc.gridy++;
        JPanel planeSel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        planeSel.setOpaque(false);
        JLabel lblPlane = new JLabel("Plane:");
        lblPlane.setForeground(Color.WHITE);
        rbPlaneAQueue = new JRadioButton("Plane A");
        rbPlaneBQueue = new JRadioButton("Plane B");
        rbPlaneCQueue = new JRadioButton("Plane C");
        stylePlaneRadio(rbPlaneAQueue);
        stylePlaneRadio(rbPlaneBQueue);
        stylePlaneRadio(rbPlaneCQueue);
        ButtonGroup bgQueuePlane = new ButtonGroup();
        bgQueuePlane.add(rbPlaneAQueue);
        bgQueuePlane.add(rbPlaneBQueue);
        bgQueuePlane.add(rbPlaneCQueue);
        planeSel.add(lblPlane);
        planeSel.add(rbPlaneAQueue);
        planeSel.add(rbPlaneBQueue);
        planeSel.add(rbPlaneCQueue);
        root.add(planeSel, gbc);

        ActionListener queuePlaneListener = e -> {
            lockedPlane = getSelectedPlaneFromQueue();
            syncPlaneSelectors();
            refreshQueueDisplay();
            refreshSeatingQueue();
            updateSeatGrid();
            refreshBoardingGrid();
        };
        rbPlaneAQueue.addActionListener(queuePlaneListener);
        rbPlaneBQueue.addActionListener(queuePlaneListener);
        rbPlaneCQueue.addActionListener(queuePlaneListener);

        gbc.gridy++;
        lblDisplayStatus = new JLabel(" ", SwingConstants.CENTER);
        lblDisplayStatus.setForeground(Color.WHITE);
        lblDisplayStatus.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        root.add(lblDisplayStatus, gbc);

        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;

        txtQueue = new JTextArea();
        txtQueue.setEditable(false);
        txtQueue.setLineWrap(true);
        txtQueue.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(txtQueue);
        sp.setPreferredSize(new Dimension(500, 260));
        root.add(sp, gbc);

        gbc.gridx = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;

        JPanel btnPanel = new JPanel();
        btnPanel.setOpaque(false);
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.Y_AXIS));

        JButton btnHome    = createYellowButton("Enqueue");
        JButton btnDequeue = createYellowButton("Dequeue");
        JButton btnShow    = createYellowButton("Show Queue");
        JButton btnClear   = createYellowButton("Clear Queue");

        btnHome.addActionListener(e -> showCard(CARD_HOME));
        btnDequeue.addActionListener(e -> dequeuePassenger());
        btnShow.addActionListener(e -> {
            refreshQueueDisplay();
            lblDisplayStatus.setForeground(Color.WHITE);
            lblDisplayStatus.setText("Queue refreshed.");
        });
        btnClear.addActionListener(e -> clearQueueAndBoardAll());

        btnPanel.add(btnHome);
        btnPanel.add(Box.createVerticalStrut(10));
        btnPanel.add(btnDequeue);
        btnPanel.add(Box.createVerticalStrut(10));
        btnPanel.add(btnShow);
        btnPanel.add(Box.createVerticalStrut(10));
        btnPanel.add(btnClear);

        root.add(btnPanel, gbc);
        return root;
    }

    private Plane getSelectedPlaneFromQueue() {
        if (rbPlaneBQueue.isSelected()) return Plane.B;
        if (rbPlaneCQueue.isSelected()) return Plane.C;
        return Plane.A;
    }

    private JButton createYellowButton(String text) {
        JButton b = new JButton(text);
        b.setBackground(YELLOW);
        b.setForeground(Color.BLACK);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(160, 35));
        b.setHorizontalTextPosition(SwingConstants.CENTER);
        b.setVerticalTextPosition(SwingConstants.TOP);
        return b;
    }

    private void dequeuePassenger() {
        if (lockedPlane == null) {
            lblDisplayStatus.setForeground(new Color(255, 200, 200));
            lblDisplayStatus.setText("No plane selected.");
            return;
        }

        Queue<Passenger> q = planeQueues.get(lockedPlane);
        Passenger p = q.poll();
        if (p == null) {
            lblDisplayStatus.setForeground(new Color(255, 200, 200));
            lblDisplayStatus.setText("Queue for Plane " + lockedPlane.name() + " is empty.");
            return;
        }

        p.boarded = true;
        Db.updateBoarded(lockedPlane.name(), p.name, p.seatCode, true);

        currentPassengerIndex = 0;
        updateCurrentPassenger();
        refreshQueueDisplay();
        refreshSeatingQueue();
        updateSeatGrid();
        refreshBoardingGrid();

        lblDisplayStatus.setForeground(new Color(200, 255, 200));
        lblDisplayStatus.setText(p.name + " has boarded from Plane " + lockedPlane.name() + ".");
    }

    private void clearQueueAndBoardAll() {
        if (lockedPlane == null) {
            lblDisplayStatus.setForeground(new Color(255, 200, 200));
            lblDisplayStatus.setText("No plane selected.");
            return;
        }

        Queue<Passenger> q = planeQueues.get(lockedPlane);
        if (q.isEmpty()) {
            lblDisplayStatus.setForeground(new Color(255, 200, 200));
            lblDisplayStatus.setText("Queue for Plane " + lockedPlane.name() + " is already empty.");
            return;
        }

        for (Passenger p : q) {
            p.boarded = true;
            Db.updateBoarded(lockedPlane.name(), p.name, p.seatCode, true);
        }
        int boardedCount = q.size();
        q.clear();

        currentPassengerIndex = 0;
        updateCurrentPassenger();

        refreshQueueDisplay();
        refreshSeatingQueue();
        updateSeatGrid();
        refreshBoardingGrid();

        lblDisplayStatus.setForeground(new Color(200, 255, 200));
        lblDisplayStatus.setText(
                "Boarded all " + boardedCount + " passenger(s) from Plane " + lockedPlane.name() + " queue.");
    }

    private void refreshQueueDisplay() {
        if (txtQueue == null) return;
        if (lockedPlane == null) {
            txtQueue.setText("No plane selected.");
            if (lblPlaneQueue != null) lblPlaneQueue.setText("Current Plane: (none)");
            return;
        }
        if (lblPlaneQueue != null) lblPlaneQueue.setText("Current Plane: " + lockedPlane.name());
        Queue<Passenger> q = planeQueues.get(lockedPlane);
        if (q.isEmpty()) {
            txtQueue.setText("Queue for Plane " + lockedPlane.name() + " is empty.");
            return;
        }
        StringBuilder sb = new StringBuilder("Plane " + lockedPlane.name() + " queue (FIFO):\n");
        for (Passenger p : q) sb.append(p).append("\n");
        txtQueue.setText(sb.toString());
    }

    /* ===================== Seating panel ===================== */
    private JPanel createSeatingPanel() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(BG_DARK);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 10, 8, 10);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;

        JLabel lblTitle = new JLabel("Plane Seating Arrangement");
        lblTitle.setForeground(YELLOW);
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 24));
        root.add(lblTitle, gbc);

        gbc.gridy++;
        lblPlaneSeating = new JLabel("Current Plane: (none)");
        lblPlaneSeating.setForeground(Color.WHITE);
        lblPlaneSeating.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        root.add(lblPlaneSeating, gbc);

        gbc.gridy++;
        gbc.gridwidth = 2;
        JPanel planeSel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        planeSel.setOpaque(false);
        JLabel lblPlane = new JLabel("Plane:");
        lblPlane.setForeground(Color.WHITE);
        rbPlaneASeat = new JRadioButton("Plane A");
        rbPlaneBSeat = new JRadioButton("Plane B");
        rbPlaneCSeat = new JRadioButton("Plane C");
        stylePlaneRadio(rbPlaneASeat);
        stylePlaneRadio(rbPlaneBSeat);
        stylePlaneRadio(rbPlaneCSeat);
        ButtonGroup bgSeatPlane = new ButtonGroup();
        bgSeatPlane.add(rbPlaneASeat);
        bgSeatPlane.add(rbPlaneBSeat);
        bgSeatPlane.add(rbPlaneCSeat);
        planeSel.add(lblPlane);
        planeSel.add(rbPlaneASeat);
        planeSel.add(rbPlaneBSeat);
        planeSel.add(rbPlaneCSeat);
        root.add(planeSel, gbc);

        ActionListener seatPlaneListener = e -> {
            lockedPlane = getSelectedPlaneFromSeat();
            syncPlaneSelectors();
            updateCurrentPassenger();
            updateSeatGrid();
            refreshSeatingQueue();
            refreshBoardingGrid();
        };
        rbPlaneASeat.addActionListener(seatPlaneListener);
        rbPlaneBSeat.addActionListener(seatPlaneListener);
        rbPlaneCSeat.addActionListener(seatPlaneListener);

        gbc.gridy++;
        lblCurrentPassenger = new JLabel("Current Passenger: (none)");
        lblCurrentPassenger.setForeground(Color.WHITE);
        lblCurrentPassenger.setFont(new Font("Segoe UI", Font.PLAIN, 13));

        JPanel cpPanel = new JPanel(new GridBagLayout());
        cpPanel.setOpaque(false);
        GridBagConstraints cpG = new GridBagConstraints();
        cpG.insets = new Insets(0, 8, 0, 8);
        cpG.gridy = 0;

        btnPrevPassenger = createYellowButton("Prev");
        btnNextPassenger = createYellowButton("Next");
        Dimension navSize = new Dimension(90, 28);
        btnPrevPassenger.setPreferredSize(navSize);
        btnNextPassenger.setPreferredSize(navSize);

        btnPrevPassenger.addActionListener(e -> movePrevPassenger());
        btnNextPassenger.addActionListener(e -> moveNextPassenger());

        cpG.gridx = 0; cpPanel.add(btnPrevPassenger, cpG);
        cpG.gridx = 1; cpPanel.add(lblCurrentPassenger, cpG);
        cpG.gridx = 2; cpPanel.add(btnNextPassenger, cpG);

        gbc.gridwidth = 2;
        root.add(cpPanel, gbc);

        gbc.gridy++;
        JPanel queuePanel = new JPanel(new BorderLayout());
        queuePanel.setOpaque(false);
        JLabel lblQ = new JLabel("FIFO Queue (Front → Back):");
        lblQ.setForeground(Color.WHITE);
        lblQ.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        queuePanel.add(lblQ, BorderLayout.NORTH);

        txtQueueSeating = new JTextArea(2, 60);
        txtQueueSeating.setEditable(false);
        txtQueueSeating.setLineWrap(true);
        txtQueueSeating.setWrapStyleWord(true);
        txtQueueSeating.setBackground(new Color(10,20,40));
        txtQueueSeating.setForeground(Color.WHITE);
        txtQueueSeating.setBorder(BorderFactory.createEmptyBorder(4,6,4,6));
        queuePanel.add(txtQueueSeating, BorderLayout.CENTER);
        root.add(queuePanel, gbc);

        gbc.gridy++;
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.CENTER, 30, 4));
        legend.setOpaque(false);
        legend.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
        legend.add(legendItem("Occupied", OCCUPIED_COLOR));
        legend.add(legendItem("Reserved", RESERVED_COLOR));
        legend.add(legendItem("Unoccupied", UNOCCUPIED_COLOR));
        root.add(legend, gbc);

        gbc.gridy++;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        seatGridPanel = new JPanel(new GridBagLayout());
        seatGridPanel.setOpaque(false);
        JScrollPane seatScroll = new JScrollPane(seatGridPanel);
        seatScroll.setOpaque(false);
        seatScroll.getViewport().setOpaque(false);
        seatScroll.setBorder(null);
        seatScroll.setPreferredSize(new Dimension(750, 280));
        root.add(seatScroll, gbc);

        gbc.gridy++;
        gbc.weightx = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 0));
        bottom.setOpaque(false);
        btnBackHomeSeating = createYellowButton("Home");
        JButton btnQueue = createYellowButton("Queue");
        JButton btnBoard = createYellowButton("Boarding");
        btnBackHomeSeating.addActionListener(e -> goHomeAndUnlockPlane());
        btnQueue.addActionListener(e -> showCard(CARD_DISPLAY));
        btnBoard.addActionListener(e -> showCard(CARD_BOARD));
        bottom.add(btnBackHomeSeating);
        bottom.add(btnQueue);
        bottom.add(btnBoard);
        root.add(bottom, gbc);

        return root;
    }

    private void stylePlaneRadio(JRadioButton rb) {
        rb.setBackground(BG_DARK);
        rb.setForeground(Color.WHITE);
    }

    private JPanel legendItem(String text, Color color) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        p.setOpaque(false);
        JPanel swatch = new JPanel();
        swatch.setBackground(color);
        swatch.setPreferredSize(new Dimension(22, 14));
        swatch.setBorder(BorderFactory.createLineBorder(SEAT_BORDER));
        JLabel lbl = new JLabel(text);
        lbl.setForeground(Color.WHITE);
        p.add(swatch);
        p.add(lbl);
        return p;
    }

    private Plane getSelectedPlaneFromSeat() {
        if (rbPlaneBSeat.isSelected()) return Plane.B;
        if (rbPlaneCSeat.isSelected()) return Plane.C;
        return Plane.A;
    }

    private void movePrevPassenger() {
        if (lockedPlane == null) return;
        Queue<Passenger> q = planeQueues.get(lockedPlane);
        if (q.isEmpty()) return;
        if (currentPassengerIndex > 0) currentPassengerIndex--;
        updateCurrentPassenger();
        refreshSeatingQueue();
    }

    private void moveNextPassenger() {
        if (lockedPlane == null) return;
        Queue<Passenger> q = planeQueues.get(lockedPlane);
        if (q.isEmpty()) return;
        if (currentPassengerIndex < q.size() - 1) currentPassengerIndex++;
        updateCurrentPassenger();
        refreshSeatingQueue();
    }

    private void updateCurrentPassenger() {
        if (lockedPlane == null) {
            currentPassenger = null;
            currentPassengerIndex = 0;
        } else {
            Queue<Passenger> q = planeQueues.get(lockedPlane);
            if (q.isEmpty()) {
                currentPassenger = null;
                currentPassengerIndex = 0;
            } else {
                int idx = 0;
                Passenger found = null;
                for (Passenger p : q) {
                    if (idx == currentPassengerIndex) { found = p; break; }
                    idx++;
                }
                currentPassenger = found;
            }
        }
        if (currentPassenger == null) {
            lblCurrentPassenger.setText("Current Passenger: (none)");
        } else {
            lblCurrentPassenger.setText("Current Passenger: " + currentPassenger);
        }
        updatePrevNextEnabled();
    }

    private void updatePrevNextEnabled() {
        if (btnPrevPassenger == null || btnNextPassenger == null) return;
        if (lockedPlane == null) {
            btnPrevPassenger.setEnabled(false);
            btnNextPassenger.setEnabled(false);
            return;
        }
        Queue<Passenger> q = planeQueues.get(lockedPlane);
        if (q.isEmpty()) {
            btnPrevPassenger.setEnabled(false);
            btnNextPassenger.setEnabled(false);
            return;
        }
        btnPrevPassenger.setEnabled(currentPassengerIndex > 0);
        btnNextPassenger.setEnabled(currentPassengerIndex < q.size() - 1);
    }

    private void refreshSeatingQueue() {
        if (txtQueueSeating == null) return;
        if (lockedPlane == null) {
            txtQueueSeating.setText("No plane selected.");
            return;
        }
        Queue<Passenger> q = planeQueues.get(lockedPlane);
        if (q.isEmpty()) {
            txtQueueSeating.setText("Queue for Plane " + lockedPlane.name() + " is empty.");
            return;
        }
        StringBuilder sb = new StringBuilder("Plane " + lockedPlane.name() + ": ");
        int idx = 0;
        for (Passenger p : q) {
            if (idx > 0) sb.append("  →  ");
            if (idx == currentPassengerIndex) sb.append("*");
            sb.append(p.name);
            if (p.seatCode != null) sb.append("[").append(p.seatCode).append("]");
            if (idx == currentPassengerIndex) sb.append("*");
            idx++;
        }
        txtQueueSeating.setText(sb.toString());
    }

    private void updateSeatGrid() {
        if (seatGridPanel == null) return;
        seatGridPanel.removeAll();
        seatButtons.clear();

        if (lockedPlane == null) {
            lblPlaneSeating.setText("Current Plane: (none)");
            seatGridPanel.revalidate();
            seatGridPanel.repaint();
            return;
        }

        lblPlaneSeating.setText("Current Plane: " + lockedPlane.name());
        Map<String, SeatStatus> map = seatMaps.get(lockedPlane);

        String[] leftCols  = {"A", "B"};
        String[] midCols   = {"C", "D", "E", "F"};
        String[] rightCols = {"H", "J"};

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2,4,2,4);
        gbc.gridy = 0;

        gbc.gridx = 0;
        seatGridPanel.add(new JLabel(""), gbc);
        for (String c : leftCols)  { gbc.gridx++; seatGridPanel.add(headerLabel(c), gbc); }
        gbc.gridx++; seatGridPanel.add(new JLabel(" "), gbc);
        for (String c : midCols)   { gbc.gridx++; seatGridPanel.add(headerLabel(c), gbc); }
        gbc.gridx++; seatGridPanel.add(new JLabel(" "), gbc);
        for (String c : rightCols) { gbc.gridx++; seatGridPanel.add(headerLabel(c), gbc); }

        for (int row = 1; row <= 15; row++) {
            gbc.gridy++;
            gbc.gridx = 0;
            JLabel rowLbl = new JLabel(String.valueOf(row));
            rowLbl.setForeground(Color.WHITE);
            rowLbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            seatGridPanel.add(rowLbl, gbc);

            for (String c : leftCols) {
                gbc.gridx++;
                seatGridPanel.add(createSeatButton(row, c, map), gbc);
            }

            gbc.gridx++;
            seatGridPanel.add(new JLabel(" "), gbc);

            for (String c : midCols) {
                gbc.gridx++;
                seatGridPanel.add(createSeatButton(row, c, map), gbc);
            }

            gbc.gridx++;
            seatGridPanel.add(new JLabel(" "), gbc);

            for (String c : rightCols) {
                gbc.gridx++;
                seatGridPanel.add(createSeatButton(row, c, map), gbc);
            }
        }

        seatGridPanel.revalidate();
        seatGridPanel.repaint();
    }

    private JLabel headerLabel(String text) {
        JLabel lbl = new JLabel(text, SwingConstants.CENTER);
        lbl.setForeground(Color.WHITE);
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 11));
        return lbl;
    }

    private JComponent createSeatButton(int row, String col, Map<String, SeatStatus> map) {
        String code = row + col;
        SeatStatus status = map.get(code);
        JButton btn = new JButton(code);
        btn.setMargin(new Insets(1,4,1,4));
        btn.setPreferredSize(new Dimension(48, 26));
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createLineBorder(SEAT_BORDER, 1));
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        applySeatStyle(btn, status);
        seatButtons.put(lockedPlane.name() + "-" + code, btn);

        btn.addActionListener(e -> handleSeatClick(code, row, btn));
        return btn;
    }

    private void showSeatUnavailableMessage(String seatCode, SeatStatus status, Passenger seatOwner) {
        String ownerName = (seatOwner != null && seatOwner.name != null && !seatOwner.name.isEmpty())
                ? seatOwner.name
                : "another passenger";

        String msg;
        if (status == SeatStatus.RESERVED) {
            msg = "Seat " + seatCode + " is reserved by " + ownerName + ".";
        } else if (status == SeatStatus.OCCUPIED) {
            msg = "Seat " + seatCode + " is already taken by " + ownerName + ".";
        } else {
            msg = "Seat " + seatCode + " is not available.";
        }

        JOptionPane.showMessageDialog(
                this,
                msg,
                "Seat not available",
                JOptionPane.WARNING_MESSAGE
        );
    }

    private void handleSeatClick(String seatCode, int row, JButton btn) {
        if (lockedPlane == null) return;
        updateCurrentPassenger();
        if (currentPassenger == null) {
            JOptionPane.showMessageDialog(this, "No current passenger.", "No passenger",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        if ("Business".equals(currentPassenger.type)) {
            if (row < 1 || row > 2) {
                JOptionPane.showMessageDialog(this,
                        "Business passenger can only sit in rows 1–2.",
                        "Row restriction", JOptionPane.WARNING_MESSAGE);
                return;
            }
        } else {
            if (row < 3 || row > 15) {
                JOptionPane.showMessageDialog(this,
                        "Economy passenger can only sit in rows 3–15.",
                        "Row restriction", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        Map<String, SeatStatus> map = seatMaps.get(lockedPlane);
        Map<String, Passenger> ass = seatOwners.get(lockedPlane);
        SeatStatus seatStatus = map.get(seatCode);
        Passenger seatOwner = ass.get(seatCode);

        if (seatOwner != null && seatOwner != currentPassenger) {
            showSeatUnavailableMessage(seatCode, seatStatus, seatOwner);
            return;
        }

        if (currentPassenger.seatCode == null) {
            if (seatStatus != SeatStatus.UNOCCUPIED) {
                showSeatUnavailableMessage(seatCode, seatStatus, seatOwner);
                return;
            }
            currentPassenger.seatCode = seatCode;
            ass.put(seatCode, currentPassenger);
            SeatStatus newStatus = currentPassenger.reservedLocked ? SeatStatus.RESERVED : SeatStatus.OCCUPIED;
            map.put(seatCode, newStatus);
            applySeatStyle(btn, newStatus);
            Db.updateSeat(lockedPlane.name(), currentPassenger.name, currentPassenger.seatCode,
                    currentPassenger.reservedLocked, currentPassenger.boarded);
        } else {
            String oldSeat = currentPassenger.seatCode;
            if (seatCode.equals(oldSeat)) return;
            if (seatStatus != SeatStatus.UNOCCUPIED) {
                showSeatUnavailableMessage(seatCode, seatStatus, seatOwner);
                return;
            }
            map.put(oldSeat, SeatStatus.UNOCCUPIED);
            ass.remove(oldSeat);
            JButton oldBtn = seatButtons.get(lockedPlane.name() + "-" + oldSeat);
            if (oldBtn != null) applySeatStyle(oldBtn, SeatStatus.UNOCCUPIED);

            currentPassenger.seatCode = seatCode;
            ass.put(seatCode, currentPassenger);
            SeatStatus newStatus = currentPassenger.reservedLocked ? SeatStatus.RESERVED : SeatStatus.OCCUPIED;
            map.put(seatCode, newStatus);
            applySeatStyle(btn, newStatus);

            Db.updateSeat(lockedPlane.name(), currentPassenger.name, currentPassenger.seatCode,
                    currentPassenger.reservedLocked, currentPassenger.boarded);
        }

        refreshSeatingQueue();
        refreshBoardingGrid();
    }

    private void applySeatStyle(AbstractButton btn, SeatStatus status) {
        if (status == SeatStatus.OCCUPIED) {
            btn.setBackground(OCCUPIED_COLOR);
        } else if (status == SeatStatus.RESERVED) {
            btn.setBackground(RESERVED_COLOR);
        } else {
            btn.setBackground(UNOCCUPIED_COLOR);
        }
        btn.setForeground(Color.BLACK);
    }

    /* ===================== Boarding panel ===================== */
    private JPanel createBoardingPanel() {
        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(BG_DARK);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5,10,5,10);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;

        JLabel title = new JLabel("Boarding Status");
        title.setForeground(YELLOW);
        title.setFont(new Font("Segoe UI", Font.BOLD, 24));
        root.add(title, gbc);

        gbc.gridy++;
        lblPlaneBoard = new JLabel("Current Plane: (none)");
        lblPlaneBoard.setForeground(Color.WHITE);
        lblPlaneBoard.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        root.add(lblPlaneBoard, gbc);

        gbc.gridy++;
        JPanel planeSel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        planeSel.setOpaque(false);
        JLabel lblPlane = new JLabel("Plane:");
        lblPlane.setForeground(Color.WHITE);
        rbPlaneABoard = new JRadioButton("Plane A");
        rbPlaneBBoard = new JRadioButton("Plane B");
        rbPlaneCBoard = new JRadioButton("Plane C");
        stylePlaneRadio(rbPlaneABoard);
        stylePlaneRadio(rbPlaneBBoard);
        stylePlaneRadio(rbPlaneCBoard);
        ButtonGroup bgBoardPlane = new ButtonGroup();
        bgBoardPlane.add(rbPlaneABoard);
        bgBoardPlane.add(rbPlaneBBoard);
        bgBoardPlane.add(rbPlaneCBoard);
        planeSel.add(lblPlane);
        planeSel.add(rbPlaneABoard);
        planeSel.add(rbPlaneBBoard);
        planeSel.add(rbPlaneCBoard);
        root.add(planeSel, gbc);

        ActionListener boardPlaneListener = e -> {
            lockedPlane = getSelectedPlaneFromBoard();
            syncPlaneSelectors();
            updateCurrentPassenger();
            updateSeatGrid();
            refreshSeatingQueue();
            refreshBoardingGrid();
        };
        rbPlaneABoard.addActionListener(boardPlaneListener);
        rbPlaneBBoard.addActionListener(boardPlaneListener);
        rbPlaneCBoard.addActionListener(boardPlaneListener);

        gbc.gridy++;
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.CENTER, 30, 4));
        legend.setOpaque(false);
        legend.add(boardLegend("Boarded", BOARDED_COLOR));
        legend.add(boardLegend("Unboarded", UNBOARDED_COLOR));
        legend.add(boardLegend("Vacant", VACANT_BOARDING));
        root.add(legend, gbc);

        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        boardingGridPanel = new JPanel(new GridBagLayout());
        boardingGridPanel.setOpaque(false);
        JScrollPane sp = new JScrollPane(boardingGridPanel);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(null);
        sp.setPreferredSize(new Dimension(750, 320));
        root.add(sp, gbc);

        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.weightx = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 0));
        bottom.setOpaque(false);
        JButton btnHome  = createYellowButton("Home");
        JButton btnQueue = createYellowButton("Queue");
        JButton btnSeat  = createYellowButton("Seating");
        JButton btnFly   = createYellowButton("Fly");

        btnHome.addActionListener(e -> goHomeAndUnlockPlane());
        btnQueue.addActionListener(e -> showCard(CARD_DISPLAY));
        btnSeat.addActionListener(e -> showCard(CARD_SEATING));

        btnFly.addActionListener(e -> flyCurrentPlane());

        bottom.add(btnHome);
        bottom.add(btnQueue);
        bottom.add(btnSeat);
        bottom.add(btnFly);
        root.add(bottom, gbc);

        return root;
    }

    private Plane getSelectedPlaneFromBoard() {
        if (rbPlaneBBoard.isSelected()) return Plane.B;
        if (rbPlaneCBoard.isSelected()) return Plane.C;
        return Plane.A;
    }

    private JPanel boardLegend(String text, Color color) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        p.setOpaque(false);
        JPanel sw = new JPanel();
        sw.setBackground(color);
        sw.setPreferredSize(new Dimension(22,14));
        sw.setBorder(BorderFactory.createLineBorder(Color.WHITE));
        JLabel lbl = new JLabel(text);
        lbl.setForeground(Color.WHITE);
        p.add(sw);
        p.add(lbl);
        return p;
    }

    private void flyCurrentPlane() {
        if (lockedPlane == null) {
            JOptionPane.showMessageDialog(this,
                    "No plane selected.",
                    "Cannot Fly",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        Plane plane = lockedPlane;

        planeQueues.get(plane).clear();
        allPax.get(plane).clear();
        seatOwners.get(plane).clear();

        Map<String, SeatStatus> map = seatMaps.get(plane);
        for (String key : map.keySet()) {
            map.put(key, SeatStatus.UNOCCUPIED);
        }

        currentPassenger = null;
        currentPassengerIndex = 0;

        Db.clearPlane(plane.name());

        refreshQueueDisplay();
        refreshSeatingQueue();
        updateSeatGrid();
        refreshBoardingGrid();

        JOptionPane.showMessageDialog(this,
                "Plane " + plane.name() + " has flown. All seats and queues have been reset.",
                "Plane Flown",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void refreshBoardingGrid() {
        if (boardingGridPanel == null) return;
        boardingGridPanel.removeAll();

        if (lockedPlane == null) {
            JLabel lbl = new JLabel("No plane selected.");
            lbl.setForeground(Color.WHITE);
            boardingGridPanel.add(lbl, new GridBagConstraints());
            boardingGridPanel.revalidate();
            boardingGridPanel.repaint();
            return;
        }

        lblPlaneBoard.setText("Current Plane: " + lockedPlane.name());
        Map<String, Passenger> ass = seatOwners.get(lockedPlane);

        String[] leftCols  = {"A", "B"};
        String[] midCols   = {"C", "D", "E", "F"};
        String[] rightCols = {"H", "J"};

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2,4,2,4);
        gbc.gridy = 0;

        gbc.gridx = 0;
        boardingGridPanel.add(new JLabel(""), gbc);
        for (String c : leftCols)  { gbc.gridx++; boardingGridPanel.add(headerLabel(c), gbc); }
        gbc.gridx++; boardingGridPanel.add(new JLabel(" "), gbc);
        for (String c : midCols)   { gbc.gridx++; boardingGridPanel.add(headerLabel(c), gbc); }
        gbc.gridx++; boardingGridPanel.add(new JLabel(" "), gbc);
        for (String c : rightCols) { gbc.gridx++; boardingGridPanel.add(headerLabel(c), gbc); }

        for (int row = 1; row <= 15; row++) {
            gbc.gridy++;
            gbc.gridx = 0;
            JLabel rowLbl = new JLabel(String.valueOf(row));
            rowLbl.setForeground(Color.WHITE);
            rowLbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            boardingGridPanel.add(rowLbl, gbc);

            for (String c : leftCols) {
                gbc.gridx++;
                boardingGridPanel.add(createBoardSeatButton(row, c, ass), gbc);
            }
            gbc.gridx++;
            boardingGridPanel.add(new JLabel(" "), gbc);
            for (String c : midCols) {
                gbc.gridx++;
                boardingGridPanel.add(createBoardSeatButton(row, c, ass), gbc);
            }
            gbc.gridx++;
            boardingGridPanel.add(new JLabel(" "), gbc);
            for (String c : rightCols) {
                gbc.gridx++;
                boardingGridPanel.add(createBoardSeatButton(row, c, ass), gbc);
            }
        }

        boardingGridPanel.revalidate();
        boardingGridPanel.repaint();
    }

    private JButton createBoardSeatButton(int row, String col, Map<String, Passenger> ass) {
        String code = row + col;
        JButton btn = new JButton(code);
        btn.setMargin(new Insets(1,4,1,4));
        btn.setPreferredSize(new Dimension(48, 26));
        btn.setFocusPainted(false);
        btn.setEnabled(false);
        btn.setBorder(BorderFactory.createLineBorder(SEAT_BORDER, 1));
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 10));

        Passenger p = ass.get(code);
        if (p == null) {
            btn.setBackground(VACANT_BOARDING);
            btn.setForeground(Color.BLACK);
        } else if (p.boarded) {
            btn.setBackground(BOARDED_COLOR);
            btn.setForeground(Color.BLACK);
        } else {
            btn.setBackground(UNBOARDED_COLOR);
            btn.setForeground(Color.BLACK);
        }

        return btn;
    }

    /* ===================== Plane selector sync ===================== */
    private void syncPlaneSelectors() {
        if (lockedPlane == null) {
            lockedPlane = Plane.A;
        }

        if (rbPlaneASeat != null) {
            rbPlaneASeat.setSelected(lockedPlane == Plane.A);
            rbPlaneBSeat.setSelected(lockedPlane == Plane.B);
            rbPlaneCSeat.setSelected(lockedPlane == Plane.C);
        }
        if (rbPlaneAQueue != null) {
            rbPlaneAQueue.setSelected(lockedPlane == Plane.A);
            rbPlaneBQueue.setSelected(lockedPlane == Plane.B);
            rbPlaneCQueue.setSelected(lockedPlane == Plane.C);
        }
        if (rbPlaneABoard != null) {
            rbPlaneABoard.setSelected(lockedPlane == Plane.A);
            rbPlaneBBoard.setSelected(lockedPlane == Plane.B);
            rbPlaneCBoard.setSelected(lockedPlane == Plane.C);
        }
    }

    /* ===================== Main ===================== */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            AirplanePassengerQueueSystem f = new AirplanePassengerQueueSystem();
            f.setVisible(true);
        });
    }
}
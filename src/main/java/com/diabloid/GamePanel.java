package com.diabloid;

import javax.swing.JPanel;
import java.awt.BorderLayout;

final class GamePanel extends JPanel {
    private final DiabloidGame.GamePanel legacyPanel;

    GamePanel() {
        super(new BorderLayout());
        legacyPanel = new DiabloidGame.GamePanel();
        add(legacyPanel, BorderLayout.CENTER);
    }
}

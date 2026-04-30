package com.diabloid;

enum PerkBranch {
    ATTACK("Атака"),
    DEFENSE("Защита"),
    SUPPORT("Поддержка");

    final String title;

    PerkBranch(String title) {
        this.title = title;
    }
}

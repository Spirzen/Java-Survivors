package com.diabloid;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

final class SaveData implements Serializable {
    int highScore = 0;
    int bestMultiKill = 0;
    double bestNoDamageSeconds = 0;
    int totalCoins = 0;
    int savedRunCoins = 0;
    int savedScore = 0;
    int savedWave = 1;
    double savedWorldTime = 0;
    String savedCharacter = "Astra";
    Set<String> unlockedCharacters = new HashSet<>();
}

package com.diabloid;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

final class SaveSystem {
    private final File saveFile = new File(System.getProperty("user.home"), ".java-survivors-save.dat");

    SaveData load() {
        if (!saveFile.exists()) return new SaveData();
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(saveFile))) {
            Object obj = in.readObject();
            if (obj instanceof SaveData data) return data;
        } catch (Exception ignored) {
        }
        return new SaveData();
    }

    void save(SaveData data) {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(saveFile))) {
            out.writeObject(data);
        } catch (Exception ignored) {
        }
    }
}

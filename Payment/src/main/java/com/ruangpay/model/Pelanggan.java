package com.ruangpay.model;

import java.time.Instant;

public final class Pelanggan {
    private final int id;
    private final String name;
    private String email;
    private String phoneNumber;
    private final Instant createdAt;

    public Pelanggan(int id, String name, String email, String phoneNumber) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.createdAt = Instant.now();
    }

    public void ubahKontak(String email, String phoneNumber) {
        this.email = email;
        this.phoneNumber = phoneNumber;
    }

    public String getInfo() { return name + " · " + email + " · " + phoneNumber; }
    public int getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhoneNumber() { return phoneNumber; }
    public Instant getCreatedAt() { return createdAt; }
}

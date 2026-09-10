package com.smarsh.discoveryhub.generator;

import java.util.List;

/**
 * The 20+ fictional employees (custodians) the corpus is built around (FR-1.2).
 * Each has an email address used as the participant identifier throughout the
 * platform.
 */
public final class Custodians {

    public record Custodian(String name, String email, String department) {
    }

    public static final List<Custodian> ALL = List.of(
            new Custodian("Alice Chen", "alice.chen@smarsh.com", "Compliance"),
            new Custodian("Bob Patel", "bob.patel@smarsh.com", "Trading"),
            new Custodian("Carla Gomez", "carla.gomez@smarsh.com", "Trading"),
            new Custodian("David Kim", "david.kim@smarsh.com", "Investment Banking"),
            new Custodian("Elena Rossi", "elena.rossi@smarsh.com", "Investment Banking"),
            new Custodian("Frank Müller", "frank.muller@smarsh.com", "Research"),
            new Custodian("Grace Lee", "grace.lee@smarsh.com", "Research"),
            new Custodian("Hiro Tanaka", "hiro.tanaka@smarsh.com", "Operations"),
            new Custodian("Isla Murphy", "isla.murphy@smarsh.com", "Operations"),
            new Custodian("Juan Alvarez", "juan.alvarez@smarsh.com", "Compliance"),
            new Custodian("Kavya Nair", "kavya.nair@smarsh.com", "Wealth Management"),
            new Custodian("Liam O'Brien", "liam.obrien@smarsh.com", "Wealth Management"),
            new Custodian("Mia Wong", "mia.wong@smarsh.com", "HR"),
            new Custodian("Noah Schmidt", "noah.schmidt@smarsh.com", "HR"),
            new Custodian("Olivia Dias", "olivia.dias@smarsh.com", "Legal"),
            new Custodian("Pierre Dubois", "pierre.dubois@smarsh.com", "Legal"),
            new Custodian("Quinn Anderson", "quinn.anderson@smarsh.com", "IT"),
            new Custodian("Riya Sharma", "riya.sharma@smarsh.com", "IT"),
            new Custodian("Sven Johansson", "sven.johansson@smarsh.com", "Finance"),
            new Custodian("Tara Singh", "tara.singh@smarsh.com", "Finance"),
            new Custodian("Uma Costa", "uma.costa@smarsh.com", "Trading"),
            new Custodian("Viktor Petrov", "viktor.petrov@smarsh.com", "Research")
    );

    private Custodians() {
    }
}

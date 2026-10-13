package net.milkbowl.vault.economy;

public class EconomyResponse {

    public double amount;
    public double balance;
    public ResponseType type;
    public String errorMessage;

    public enum ResponseType {
        SUCCESS,
        FAILURE
    }
}

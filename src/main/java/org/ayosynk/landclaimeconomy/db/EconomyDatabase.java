package org.ayosynk.landclaimeconomy.db;

import org.ayosynk.landclaimeconomy.LandClaimEconomy;
import org.ayosynk.landClaimPlugin.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the addon's database tables (prefixed {@code lce_}) inside the
 * parent's database. Reuses the parent's {@link DatabaseManager} so the
 * addon shares the same SQLite file / MySQL database — one backup, one
 * connection pool.
 */
public class EconomyDatabase {

    private final LandClaimEconomy plugin;
    private final DatabaseManager parent;

    public EconomyDatabase(LandClaimEconomy plugin, DatabaseManager parent) {
        this.plugin = plugin;
        this.parent = parent;
    }

    public String tablePrefix() {
        // Addon's own prefix so the tables are clearly separated from the parent's lc_* ones.
        return "lce_";
    }

    public DatabaseManager parent() {
        return parent;
    }

    public Connection getConnection() throws SQLException {
        return parent.getConnection();
    }

    public boolean isMySQL() {
        return parent.isMySQL();
    }

    /**
     * Create or migrate the addon's tables. Idempotent — safe to call on every
     * startup. Schema is intentionally simple so the addon stays a thin
     * layer on top of the parent.
     */
    public void createTables() {
        String p = tablePrefix();
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {

            // Transactions ledger. Records every Vault charge for auditing
            // and so admins can debug "why was I charged X?".
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + p + "transactions ("
                            + "id INTEGER PRIMARY KEY " + (isMySQL() ? "AUTO_INCREMENT" : "AUTOINCREMENT") + ","
                            + "player_uuid VARCHAR(36) NOT NULL,"
                            + "claim_profile_id VARCHAR(36),"
                            + "kind VARCHAR(32) NOT NULL,"
                            + "amount DOUBLE NOT NULL,"
                            + "note VARCHAR(255),"
                            + "created_at BIGINT NOT NULL)");

            // Active market listings (one row per listed claim).
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + p + "market_listings ("
                            + "claim_profile_id VARCHAR(36) PRIMARY KEY,"
                            + "owner_uuid VARCHAR(36) NOT NULL,"
                            + "price DOUBLE NOT NULL,"
                            + "listed_at BIGINT NOT NULL)");

            // Per-claim tax ledger. Tracks how many "tax days" the owner
            // is behind so the TaxManager can auto-unclaim when the grace
            // period expires.
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + p + "tax_ledger ("
                            + "claim_profile_id VARCHAR(36) PRIMARY KEY,"
                            + "last_paid_at BIGINT NOT NULL,"
                            + "unpaid_days INTEGER NOT NULL DEFAULT 0,"
                            + "last_known_chunks INTEGER NOT NULL DEFAULT 0)");

            // Active auctions. claim_profile_id is the natural primary
            // key — a claim can only be in one auction at a time. The
            // current bid and bidder columns are updated in place when
            // a new bid arrives.
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + p + "auctions ("
                            + "auction_id INTEGER PRIMARY KEY "
                            + (isMySQL() ? "AUTO_INCREMENT" : "AUTOINCREMENT") + ","
                            + "claim_profile_id VARCHAR(36) NOT NULL,"
                            + "seller_uuid VARCHAR(36) NOT NULL,"
                            + "starting_price DOUBLE NOT NULL,"
                            + "current_bid DOUBLE NOT NULL DEFAULT 0,"
                            + "current_bidder VARCHAR(36),"
                            + "buyout_price DOUBLE NOT NULL DEFAULT 0,"
                            + "ends_at BIGINT NOT NULL,"
                            + "started_at BIGINT NOT NULL,"
                            + "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',"
                            + "UNIQUE (claim_profile_id))");

            plugin.getLogger().info("LandClaimPlugin-Economy database tables ready (prefix '" + p + "').");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create addon tables: " + e.getMessage());
        }
    }

    public void logTransaction(String playerUuid, String claimProfileId, String kind,
                                double amount, String note) {
        String p = tablePrefix();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + p + "transactions (player_uuid, claim_profile_id, kind, amount, note, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, playerUuid);
            if (claimProfileId == null) {
                ps.setNull(2, java.sql.Types.VARCHAR);
            } else {
                ps.setString(2, claimProfileId);
            }
            ps.setString(3, kind);
            ps.setDouble(4, amount);
            ps.setString(5, note);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to log transaction: " + e.getMessage());
        }
    }
}

# LandClaimPlugin-Economy

A standalone economy addon for [LandClaimPlugin](https://github.com/synkfr/LandClaimPlugin).

Charges players for claiming chunks, setting warps, and inviting members; runs a
daily per-chunk upkeep tax with grace period and auto-unclaim; and provides a server-wide claim marketplace
and time-limited auction system with GUI browsers. Every single feature can be toggled independently.

## Features

| Feature | Description |
|---|---|
| **Claim Pricing Modes** | 3 configurable pricing types: `FIXED` (flat rates), `DOUBLED` (doubles with each chunk claimed), or `PERCENTAGE` (compounding percentage increase). Includes daily spend caps and optional first-chunk-free. |
| **Warp Costs** | Charge for setting claim warps. Public warps can carry an additional configurable multiplier, charged at creation or upon upgrading to public in the GUI. |
| **Invite Costs** | Charge for `/claim member invite` and `/claim trust invite` separately. |
| **Limit Purchases** | Buy additional claim blocks, role slots, member slots, and warp slots directly with economy currency. |
| **Daily Upkeep Tax** | Per-chunk-per-day tax with a configurable grace period. Unpaid chunks are auto-unclaimed via the parent's public API. Includes `/claimtax` to view ledger status. |
| **Marketplace** | `/claimmarket sell/buy/unlist` — server-wide claim listings with atomic database purchases, preventing double-purchasing race conditions. |
| **Auctions** | Time-limited auctions with bidding, buyout, and anti-snipe protection. Disallows cross-listing and cleans up dangling listings automatically. |
| **GUIs** | `/claimmarket` opens the MarketplaceGUI; `/claimmarket auction list` opens the AuctionGUI with live countdown timers. |

## Installation

1. Install [LandClaimPlugin](https://github.com/synkfr/LandClaimPlugin) **3.1.0+**.
2. Install [Vault](https://www.spigotmc.org/resources/vault.34315/) and any economy provider (EssentialsX Eco, CMI Economy, etc.).
3. Drop `LandClaimPlugin-Economy-3.1.0.jar` into your `plugins/` folder.
4. Restart the server.

The addon soft-depends on both LandClaimPlugin and Vault. If either is missing
at startup, the addon disables itself with a clear log message — it will not crash the server.

## Build

```bash
# 1. Build and install parent plugin to local Maven repository
cd ../LandClaimPlugin
mvn clean install -DskipTests

# 2. Package the addon
cd ../LandClaimPlugin-Eco-Addon
mvn clean package
# → target/LandClaimPlugin-Economy-3.1.0.jar
```

## Commands

### Marketplace

| Command | Description |
|---|---|
| `/claimmarket` | Open the MarketplaceGUI (falls back to AuctionGUI if market is disabled) |
| `/claimmarket mine` | Open the MarketplaceGUI filtered to your own listings |
| `/claimmarket sell <name> <price>` | List your claim for sale |
| `/claimmarket buy <name>` | Buy a listed claim (transfers ownership via `LandClaimAPI.transferClaim`) |
| `/claimmarket unlist <name>` | Remove your claim listing |

### Auctions

| Command | Description |
|---|---|
| `/claimmarket auction list` | Open the AuctionGUI (with countdown timers) |
| `/claimmarket auction start <name> <starting-price> [duration-min] [buyout]` | Start an auction |
| `/claimmarket auction bid <name> <amount>` | Place a bid (or trigger an immediate buyout) |
| `/claimmarket auction cancel <name>` | Cancel an auction with no bids |

### Taxes

| Command | Description |
|---|---|
| `/claimtax` | View your active claim's tax status, unpaid days, next tax run, and total owed |

## Configuration

Every feature has its own independent `enabled` toggle in `plugins/LandClaimPlugin-Economy/config.yml`.
The master `enabled` flag is a global kill-switch.

```yaml
# Master switch. When false, the addon does nothing.
enabled: true

# ========== Claim Cost ==========
claimCost:
  enabled: false
claimCostMode: FIXED # FIXED, DOUBLED, or PERCENTAGE
baseCost: 25.0
firstChunkCost: 100.0
perChunkCost: 25.0
percentageIncrease: 10.0
maxChunkCost: 10000.0
firstClaimFree: true
dailyChargeCap: 5000.0

# ========== Warp Cost ==========
warpCost:
  enabled: false
warpCostAmount: 50.0
publicWarpMultiplier: 2.0

# ========== Invite Cost ==========
inviteCost:
  enabled: false
memberInviteCost: 25.0
trustedInviteCost: 10.0

# ========== Limit Purchases ==========
limitPurchases:
  enabled: false
claimBlockCost: 50.0
roleSlotCost: 150.0
memberSlotCost: 50.0
warpSlotCost: 75.0

# ========== Daily Tax ==========
tax:
  enabled: false
taxPerChunkPerDay: 5.0
taxGracePeriodDays: 7
taxIntervalMinutes: 60

# ========== Marketplace ==========
market:
  enabled: false
marketListingFee: 100.0
marketMaxPrice: 1000000.0
marketSellerPayoutRatio: 1.0

# ========== Auctions ==========
auction:
  enabled: false
auctionDefaultDurationMinutes: 60.0
auctionMinBidIncrement: 1.0
auctionListingFee: 100.0
auctionCheckIntervalSeconds: 30
auctionMaxPerPlayer: 3
auctionSnipeWindowSeconds: 60
auctionMinBuyoutMultiplier: 2.0
```

## Permissions

| Permission | Description | Default |
|---|---|---|
| `landclaimeconomy.use` | Use the economy features (claim/warp costs, market) | `true` |
| `landclaimeconomy.bypass` | Bypass all economy charges (free claiming, warps, invites) | `op` |
| `landclaimeconomy.bypass.tax` | Bypass daily tax (no upkeep fees, immune to auto-unclaim) | `op` |
| `landclaimeconomy.admin` | Admin commands (cancel auctions, force-clear listings) | `op` |

## Database

The addon creates its own tables in the parent's database with an `lce_` prefix:

- `lce_transactions` — every Vault charge (audit log)
- `lce_market_listings` — active market listings
- `lce_auctions` — active and historical auctions (`ACTIVE`, `SETTLED`, `CANCELLED`)
- `lce_tax_ledger` — per-claim tax payment state (last-paid timestamp, unpaid-day count)

The addon automatically uses the parent plugin's database connection on startup (SQLite or MySQL).

## Refund & Safety Rollbacks

If a claim transfer fails after payment, both sides are automatically made whole:
- The buyer is refunded.
- The seller payout is clawed back.
- A `MARKET_REFUND` row is logged for audit.
- Console and player notifications are dispatched.

The same rollback safeguards protect against failed auction settlements.

## Compatibility

| Addon version | Parent version | Notes |
|---|---|---|
| 3.1.0 | 3.1.0+ | Supports 3 claim pricing modes, independent toggles, warp privacy fee events, and atomic marketplace purchases |
| 1.0.0 | 3.0.0 | Initial release |

## Related projects

- [synkfr/LandClaimPlugin](https://github.com/synkfr/LandClaimPlugin) — the parent plugin

## License

This addon follows the same license as the parent LandClaimPlugin.
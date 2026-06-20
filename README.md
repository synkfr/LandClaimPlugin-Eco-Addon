# LandClaimPlugin-Economy

A standalone economy addon for [LandClaimPlugin](https://github.com/synkfr/LandClaimPlugin).

Charges players for claiming chunks, setting warps, and inviting members; runs a
daily per-chunk tax with auto-unclaim; and adds a server-wide claim marketplace
and time-limited auction system with a GUI browser. Everything is toggleable so
you can enable just the features you want.

## Features

| Feature | Description |
|---|---|
| **Claim cost** | Charge players for `/claim`. First chunk ever is free; subsequent chunks cost `perChunkCost`; a daily cap protects against runaway charges. |
| **Warp cost** | Charge for `/claim setwarp`. Public warps cost more (configurable multiplier). |
| **Invite cost** | Charge for `/claim member invite` and `/claim trust invite` separately. |
| **Daily tax** | Per-chunk-per-day tax with a configurable grace period. Unpaid chunks are auto-unclaimed via the parent's public API. |
| **Marketplace** | `/claimmarket sell/buy/unlist` — server-wide claim listings. Charges a listing fee on sell, supports a seller-payout ratio so servers can take a cut. |
| **Auctions** | Time-limited auctions with `lce_auctions` table. `placeBid`/`buyout`/`cancel`. Snipe-protection extends the timer if a bid lands in the last N seconds. |
| **GUIs** | `/claimmarket` opens a paginated MarketplaceGUI; `/claimmarket auction list` opens the AuctionGUI with countdown timers. |

## Installation

1. Install [LandClaimPlugin](https://github.com/synkfr/LandClaimPlugin) **2.5.0+** (this addon uses `transferClaim` / `unclaimAll` / `getAllClaimProfiles` introduced there).
2. Install [Vault](https://www.spigotmc.org/resources/vault.34315/) and any economy provider (EssentialsX Eco, CMI Economy, etc.).
3. Drop `LandClaimPlugin-Economy-1.0.0.jar` into your `plugins/` folder.
4. Restart the server.

The addon soft-depends on both LandClaimPlugin and Vault. If either is missing
at startup, the addon disables itself with a clear log line — it won't crash
the server.

## Build

```bash
# Build the parent first so the addon can resolve it from the local repo
cd ../LandClaimPlugin
mvn install -DskipTests

# Then build the addon
cd ../LandClaimPlugin-Economy
mvn package
# → target/LandClaimPlugin-Economy-1.0.0.jar
```

## Commands

### Marketplace

| Command | Description |
|---|---|
| `/claimmarket` | Open the MarketplaceGUI (paginated, click to buy) |
| `/claimmarket mine` | Open the MarketplaceGUI filtered to your own listings |
| `/claimmarket sell <name> <price>` | List your claim at the given price |
| `/claimmarket buy <name>` | Buy a listed claim (transfers ownership via `transferClaim`) |
| `/claimmarket unlist <name>` | Remove your listing |

### Auctions

| Command | Description |
|---|---|
| `/claimmarket auction list` | Open the AuctionGUI (with countdown timers) |
| `/claimmarket auction start <name> <starting-price> [duration-min] [buyout]` | Start an auction |
| `/claimmarket auction bid <name> <amount>` | Place a bid (or hit the buyout) |
| `/claimmarket auction cancel <name>` | Cancel an auction with no bids |

## Configuration

Every feature has its own `enabled` toggle in `plugins/LandClaimPlugin-Economy/config.yml`.
The master `economy.enabled` flag is a global kill-switch.

```yaml
economy:
  enabled: true
  claimCost:
    enabled: false       # set to true to charge for /claim
    firstClaimCost: 100.0
    perChunkCost: 25.0
    firstClaimFree: true
    dailyChargeCap: 5000.0
  warpCost:
    enabled: false
    warpCostAmount: 50.0
    publicWarpMultiplier: 2.0
  inviteCost:
    enabled: false
    memberInviteCost: 25.0
    trustedInviteCost: 10.0
  tax:
    enabled: false
    taxPerChunkPerDay: 5.0
    taxGracePeriodDays: 7
    taxIntervalMinutes: 60
  market:
    enabled: false
    marketListingFee: 100.0
    marketMaxPrice: 1000000.0
    marketSellerPayoutRatio: 1.0
  auction:
    enabled: false
    auctionDefaultDurationMinutes: 60.0
    auctionMinBidIncrement: 1.0
    auctionListingFee: 100.0
    auctionCheckIntervalSeconds: 30
    auctionMaxPerPlayer: 3
    auctionSnipeWindowSeconds: 60   # 0 disables sniping protection
    auctionMinBuyoutMultiplier: 2.0   # buyout must be >= N * starting
```

## Permissions

| Permission | Description | Default |
|---|---|---|
| `landclaimeconomy.use` | Use the economy features (claim/warp costs, market) | `true` |
| `landclaimeconomy.bypass` | Bypass all economy charges | `op` |
| `landclaimeconomy.bypass.tax` | Bypass daily tax (no upkeep fees, no auto-unclaim) | `op` |
| `landclaimeconomy.admin` | Admin commands | `op` |

## Permissions (from LandClaimPlugin)

The addon also relies on the parent's permissions:

| Permission | Used for |
|---|---|
| `landclaim.admin` | Required by `transferClaim` and `unclaimAll` when the actor isn't the new owner / a system caller |
| `landclaim.claim` | Required to run `/claim` (so the cost charge is reachable) |

## Permissions (from Vault)

`landclaimeconomy.*` only fires Vault charges — the underlying economy
plugin's permissions apply (e.g. EssentialsX Eco's `essentials.eco` for paying
fees).

## Database

The addon creates its own tables in the parent's database with a `lce_` prefix:

- `lce_transactions` — every Vault charge (audit log)
- `lce_market_listings` — active market listings
- `lce_auctions` — active and historical auctions (with `status` column: `ACTIVE` / `SETTLED` / `CANCELLED`)
- `lce_tax_ledger` — per-claim tax payment state (last-paid timestamp, unpaid-day count)

No configuration needed — the addon picks up the parent's DB connection on
startup.

## Refund policy

If a claim transfer fails after the buyer has paid, both sides are made whole
automatically:
- the buyer's `listing.price` is refunded
- the seller's `payout` is clawed back
- a `MARKET_REFUND` row is logged for audit
- the buyer gets a chat message and an admin is notified via `plugin.getLogger().warning(...)`

The same rollback runs when an auction settlement fails.

## Compatibility

| Parent version | Addon version | Notes |
|---|---|---|
| 2.5.0+ | 1.0.0 | Uses `transferClaim` / `unclaimAll` / `getAllClaimProfiles` (added in 2.5.0) |
| < 2.5.0 | — | Not supported. The addon disables itself if it can't find the new API methods. |

## Related projects

- [synkfr/LandClaimPlugin](https://github.com/synkfr/LandClaimPlugin) — the parent plugin
- More addons welcome via PR

## License

This addon follows the same license as the parent LandClaimPlugin.
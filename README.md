<div align="center">

# ⛏️ Kore

**A lean, modern Minecraft: Bedrock Edition server engine, written from scratch in Kotlin.**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/JDK-25-ED8B00?logo=openjdk&logoColor=white)](https://adoptium.net)
[![Netty](https://img.shields.io/badge/Netty-4.1-1E8C45)](https://netty.io)
[![Transport](https://img.shields.io/badge/transport-NetherNet-62B47A)](https://minecraft.wiki/w/NetherNet)
[![Status](https://img.shields.io/badge/status-pre--alpha-orange)](#-milestones)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

[Philosophy](#-philosophy) · [Tech Stack](#-tech-stack) · [How It Connects](#-how-a-client-connects) · [Getting Started](#-getting-started) · [Milestones](#-milestones) · [Contributing](CONTRIBUTING.md)

</div>

---

> [!WARNING]
> Kore is in early development. Players can join a flat world and walk around it, but they can't change anything in it or see each other yet.
> Follow the [milestones](#-milestones) to see where things stand.

## 🧭 Philosophy

Kore is not a fork, a wrapper, or a port of an existing server. It's a ground-up engine built around a few rules:

- **Simple beats clever.** Small files, flat packages, plain functions. If a newcomer can't follow a code path in one sitting, it's too complicated.
- **Fast by default.** Non-blocking I/O end to end, no reflection-driven plugin layer, no work on the event loop that doesn't belong there.
- **Real Kotlin.** Data classes, sealed types, extension functions and lambdas. Not Java written with Kotlin syntax.
- **No legacy baggage.** No `Manager` singletons, no abstract base class towers, no Bukkit-era patterns kept around for familiarity.
- **NetherNet only.** RakNet is deprecated for Bedrock Dedicated Server. Kore targets the WebRTC-based NetherNet transport and nothing else.

## 🧰 Tech Stack

| Layer | Choice | Why |
| --- | --- | --- |
| Language | **Kotlin 2.4** on **JDK 25** | Concise, null-safe, and a first-class JVM citizen. |
| Build | **Gradle** (Kotlin DSL) | One language for the code and the build. |
| Networking | **Netty 4** | Battle-tested non-blocking I/O for both the TCP signaling and the UDP game traffic. |
| Serialization | **kotlinx.serialization** | Compile-time JSON codecs with no reflection. |
| NBT | **Cloudburst NBT** | Reads the vanilla data, and writes NBT for packets and block state hashes. No dependencies of its own. |
| Game data | **[KoreMC/data](https://github.com/KoreMC/data)** | Items, block states, biomes and the creative inventory, dumped from the game by CloudburstMC. Kept in its own repository and read from `data/` on start. |
| Security | **JDK** (DTLS 1.2, ECDSA) | The JDK's non-blocking DTLS engine fits Netty, and needs no native libraries or BouncyCastle. |
| Logging | **SLF4J + Logback** | Structured, configurable, and fast enough to stay out of the way. |

## 🔌 How a Client Connects

NetherNet splits a connection into two phases: an HTTP signaling handshake over TCP, followed by a WebRTC peer connection that carries gameplay over UDP.

```mermaid
sequenceDiagram
    participant C as Bedrock Client
    participant K as Kore (TCP :19132)

    C->>K: GET /v1/join
    K-->>C: 200 OK, server info (JSON)
    C->>K: POST /v1/join/{networkId}, SDP offer
    K-->>C: 200 OK, SDP answer
    Note over C,K: ICE checks, then DTLS and SCTP over UDP
    C-)K: Game packets over WebRTC data channels
```

| Step | What happens | Kore status |
| --- | --- | --- |
| Discovery | `GET /v1/join` returns name, version, level and player counts as JSON | ✅ Implemented |
| Offer / answer | `POST /v1/join/{networkId}` exchanges SDP with all ICE candidates included. The answer is signed with the operator identity. | ✅ Implemented |
| Connectivity | ICE checks find a UDP path, then DTLS secures it | ✅ Implemented |
| Data channels | SCTP inside DTLS carries the `ReliableDataChannel` and `UnreliableDataChannel` | ✅ Implemented |
| Framing | A one-byte header splits large messages across the reliable channel and joins them back up | ✅ Implemented |

Kore keeps its side of WebRTC as small as possible. It runs **ICE-lite** and takes the **passive DTLS** role, so it never initiates anything. Every client shares one UDP port: Kore answers their connectivity checks, then waits for each client's first DTLS message. A client's DTLS certificate is accepted only if it matches the fingerprint in that client's offer.

**SCTP is written in Kotlin**, with no native libraries. It covers only what data channels need: one path, no multihoming, and partial reliability so the unreliable channel can drop messages instead of retransmitting them.

Game code sees none of this. Each client is a `NetherNetConnection` that sends and receives whole messages, reliably or not, through three callbacks:

| Callback | When it runs |
| --- | --- |
| `onConnect` | Once, as soon as the client's reliable channel opens and it can be sent to |
| `onMessage` | For every complete message |
| `onDisconnect` | Once, with a reason, for every client `onConnect` announced: it closed, timed out, broke the protocol, or the server stopped |

All three run on the UDP event loop, so they must never block. If one throws, the error is logged and the connection carries on.

## 🚀 Getting Started

**Requirements:** JDK 25. Gradle downloads a matching toolchain if you don't have one.

```bash
git clone https://github.com/KoreMC/kore.git
cd kore
git clone https://github.com/KoreMC/data.git data
./gradlew run
```

The second clone fetches the [game data](#-vanilla-data) Kore reads on start. Kore won't start without it.

Kore uses port `19132` for both signaling (TCP) and gameplay (UDP), so open both in your firewall. Check that signaling is reachable:

```bash
curl -i http://localhost:19132/v1/join
```

```json
{"name":"Kore Server","protocol":2193,"version":"1.26.50","level":"world","players":0,"maxPlayers":20,"gameType":0}
```

The console shows what's happening at `INFO`. Each run also writes everything, at `DEBUG`, to its own file in `logs/`, named for when the server started (for example, `logs/kore-2026-10-11_14-10-57.log`). The file has:
- requests and their headers, and SDP offers;
- each step of every connection;
- every packet batch in and out, listed by packet.

Once a player is in the game, batches holding only packets Kore ignores, mostly movement, are left out. Set the `org.koremc.packets` logger to `TRACE` in [`logback.xml`](src/main/resources/logback.xml) to include them.

Player identity tokens are redacted, and logins are logged only by size. Kore never deletes old logs, so clear out `logs/` now and then.

### 🔑 The Operator Key

On first run, Kore creates `operator.key` in its working directory. Every SDP answer is signed with this key. Players connecting over plain HTTP are asked to trust it once, and then the client remembers it.

- **Keep it safe.** Anyone holding it can sign answers as your server.
- **Don't regenerate it.** A new key prompts every returning player again.
- **Share it across servers** if you run several. Players then trust them all with one prompt.

### 🛡️ Authentication

Kore runs in **online mode** by default: every player must prove who they are through Xbox Live. Each check closes off a different way of lying:

1. **The offer** carries the client's key and its signature over the connection's DTLS fingerprints. That ties the connection to a key the client holds.
2. **The login token** must be signed by Minecraft's authorization service, for the multiplayer audience, and must not have expired.
3. **The login's key** must be the one the offer proved. A login captured from one connection can't be replayed on another.
4. **The client data** (skin, device and so on) must be signed by that key.

Kore finds the authorization service through Minecraft's service discovery and keeps its signing keys cached, refreshing them in the background. So the server needs outbound HTTPS. A login that fails any check gets a `Disconnect`, and the reason goes to the log.

`onlineMode = false` turns all of this off for local testing. Anyone can then join under any name, so never run it where the public can reach it.

### 📦 Vanilla Data

Clients expect the server to agree with them about the game itself: which items and biomes exist, what the creative inventory holds, and what each block state is called on the network. [CloudburstMC/Data](https://github.com/CloudburstMC/Data) dumps that data from the game, and Kore keeps its copy in a repository of its own, [KoreMC/data](https://github.com/KoreMC/data), apart from the code. Kore reads it once on start from `data/` in its working directory (`dataDirectory` in `ServerConfig`). If a file is missing, Kore names it and stops.

- **Items** go to every player in the `ItemRegistry`, with the runtime ID each one travels by. The list is the same for everyone, so Kore encodes it once on start rather than at every login.
- **Biomes** follow in the `BiomeDefinitionList`: each one's climate, colours and tags. Kore leaves out the world generation data, which only a client generating its own terrain would use, so the packet stays under 8 KB.
- **The creative inventory** follows, as `CreativeContent`: 124 groups across the four tabs, and 1,980 items, including recent ones like the mushroom shelf and wool and concrete stairs and slabs. Each item names the block state it places and carries its NBT, like an enchanted book's enchantment. It's encoded once on start, too. Because Kore tells the client that the server owns inventories, the client can't even open its own inventory without asking: Kore answers with `ContainerOpen`, and agrees when the client closes it.
- **Blocks defined in data**, rather than in the game's code, go in `StartGame`. Since 26.50 that includes the wool and concrete stairs and slabs and the shelf mushroom. Without their definitions, the client knows these blocks exist but not how they look, and shows them as placeholders.
- **Block states** are named on the network by a hash, not by their place in a palette: the 32-bit FNV-1a hash of the state's name and properties, written as little-endian NBT. Mojang's protocol docs don't spell the rule out, so the tests check it against the network ID the game gave each of the 22,091 states in its palette.

The data must be for the same client release as `version` in `ServerConfig`.

### 🌍 The World

Until worlds load from disk, every player joins the vanilla flat world: bedrock, two layers of dirt and grass on top, all plains. They appear at `0, -60, 0`, standing on the grass.

Once the client says how far it wants to see, Kore settles on a radius no larger than `viewDistance` (8 chunks by default). It then sends every chunk within that circle whole, nearest first, so the client never has to ask for sub-chunks one at a time. Only then does the player spawn. All the chunks are identical, so the flat world builds just one and sends it everywhere.

Movement is server-authoritative in Bedrock: every tick, the client sends its input and where it predicts its player now is. A server is meant to simulate the same movement and correct the client when they disagree. Mojang's movement guide also allows a server without physics to accept the client's position as given, and for now that's what Kore does. It only rejects positions that are impossible: not a number, or millions of blocks out. Once a player strays two chunks from the centre of their view, Kore centres it on them again and sends the chunks that have come into it. Players often step back and forth over a chunk border, and if the view followed every step, each one would resend the same row of chunks. This way, the view trails a moving player by up to a chunk or so instead.

The client also needs to be told about its own player once it spawns, or it moves the player wrongly: it floats instead of falling, and counts its air down as though underwater. So Kore sends the player's actor data (it breathes, falls, collides and climbs), its attributes (full health and hunger, since sprinting needs food, and walking speed), and the abilities its game mode allows.

The input the client sends each tick also says when the player starts and stops sprinting or sneaking. Kore keeps track, and sends the change back as the player's actor data, with a sneaking player's shorter height. It tags the update with the input's tick, so the client applies it at the moment it predicted it, as Mojang's movement guide describes. The sprint's speed boost is left to the client, which already applies it.

Chunks name biomes by number, and Cloudburst's data has none for vanilla biomes. For now, plains is hard-coded as biome `1`.

> [!NOTE]
> Server settings live in [`ServerConfig.kt`](src/main/kotlin/ServerConfig.kt) until there's a config file. The defaults target 1.26.50 (protocol `2193`). Update `protocol` and `version` together, along with the [vanilla data](#-vanilla-data), when moving to a new client release. Behind NAT, set `advertisedAddresses` to your public IP so clients get a candidate they can reach.

## 🗂️ Project Layout

```text
src/main/kotlin/
├── Main.kt                          # Entry point: wires config, server and shutdown
├── ServerConfig.kt                  # Server settings
├── Session.kt                       # Each player's walk through the login sequence
├── Authentication.kt                # Deciding whether a login proves who it claims to be
├── auth/
│   ├── Jose.kt                      # Signed tokens and the keys that verify them
│   └── MultiplayerTokens.kt         # Minecraft's authorization service and its signing keys
├── protocol/
│   ├── Buffers.kt                   # Varints, strings and other wire primitives
│   ├── Batch.kt                     # Batching packets, with zlib compression
│   ├── Packets.kt                   # Packet types and their encodings
│   ├── StartGame.kt                 # The packet that sets up the world on the client
│   ├── LevelChunk.kt                # Chunk columns and paletted storage on the wire
│   ├── ActorData.kt                 # Actors' synced data and flags
│   ├── Attributes.kt                # Health, hunger, speed and other attributes
│   ├── Abilities.kt                 # What a player may do in their game mode
│   ├── CreativeContent.kt           # The creative inventory, and items on the wire
│   └── LoginRequest.kt              # Reading who a client claims to be
├── world/
│   ├── Positions.kt                 # Block, chunk and exact positions
│   ├── Player.kt                    # A player's size, breath and speed
│   ├── PalettedStorage.kt           # A sub-chunk's worth of values, as a palette and indices
│   ├── Chunk.kt                     # Chunk columns, and which are in a player's view
│   └── FlatWorld.kt                 # The same layers in every column
├── registry/
│   ├── VanillaData.kt               # Loads the game's items, block states and biomes on start
│   ├── Items.kt                     # Items, as the client's item registry lists them
│   ├── Biomes.kt                    # Biomes, as the client's biome definitions describe them
│   ├── Creative.kt                  # The creative inventory's tabs, groups and items
│   └── BlockStates.kt               # Block states and the hashes the client knows them by
└── network/
    ├── SignalingServer.kt           # TCP bootstrap and HTTP pipeline
    ├── NetherNetSignalingHandler.kt # Routes /v1/join requests
    ├── JoinInfo.kt                  # Discovery response body
    ├── RtcServer.kt                 # UDP bootstrap
    ├── NetherNetRtcHandler.kt       # Routes UDP traffic through STUN, DTLS and SCTP per client
    ├── NetherNetConnection.kt       # One client's channels, with NetherNet's message framing
    ├── rtc/
    │   ├── Sdp.kt                   # Offer parsing and answer encoding
    │   ├── LocalPeer.kt             # Builds answers from this server's WebRTC state
    │   ├── Peer.kt                  # Clients between signaling and disconnect
    │   ├── ClientIdentity.kt        # Checking the identity in a client's offer
    │   ├── Ice.kt                   # ICE credentials and host candidates
    │   ├── Stun.kt                  # STUN messages for ICE-lite
    │   ├── Dtls.kt                  # DTLS engines and per-client transport
    │   ├── DtlsCertificate.kt       # Self-signed DTLS certificate and fingerprint
    │   └── OperatorIdentity.kt      # Operator key and a=identity assertions
    └── sctp/
        ├── Chunks.kt                # SCTP packet and chunk encoding
        ├── SctpAssociation.kt       # Handshake, reassembly, retransmission and congestion control
        └── DataChannels.kt          # Opening data channels (DCEP)
```

Packages follow the Kotlin convention of omitting the common root, so `org.koremc.network` lives in `src/main/kotlin/network/`.

## 🗺️ Milestones

### Phase 1: Transport
- [x] Netty bootstrap with an HTTP/1.1 pipeline
- [x] **NetherNet HTTP Signaling:** discovery (`GET /v1/join`)
- [x] **NetherNet HTTP Signaling:** SDP offer/answer (`POST /v1/join/{networkId}`)
- [x] **UDP Channel Negotiation:** ICE-lite connectivity checks
- [x] **UDP Channel Negotiation:** DTLS 1.2 handshake with fingerprint verification
- [x] **UDP Channel Negotiation:** SCTP association and data channels
- [x] Message framing over reliable and unreliable data channels

### Phase 2: Protocol
- [x] Batches, zlib compression and the packet codec
- [x] Network settings negotiation and protocol version checks
- [x] Reading the login's connection request and client data
- [x] Verify login tokens, and bind them to the identity in the client's offer
- [x] Login success and resource pack negotiation (no server packs yet)
- [x] `StartGame` and chunk radius negotiation
- [x] **Cloudburst NBT Parsing**
- [x] Vanilla item registry and block state hashes, from CloudburstMC's data
- [x] Biome definitions
- [x] Chunks and spawning, in a flat world
- [ ] Packet registry for the rest of the protocol

### Phase 3: World
- [ ] **World Loading** (LevelDB)
- [ ] Chunk serialization
- [x] Chunk streaming as players move
- [x] Player spawning and movement, taking the client's word for its position
- [ ] Movement simulation and corrections

### Phase 4: Gameplay
- [x] Creative inventory, from CloudburstMC's data
- [ ] Block breaking and placing
- [ ] Inventories
- [ ] Entities
- [ ] Commands

## 🤝 Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. It covers the code standards and the review process.

## 📚 References

- [NetherNet HTTP Signaling: Partner Onboarding Guide](https://mojang.github.io/bedrock-protocol-docs/guides/nether-net-onboarding-guide/) from Mojang
- [NetherNet](https://minecraft.wiki/w/NetherNet) and [Bedrock Edition protocol](https://minecraft.wiki/w/Bedrock_Edition_protocol) on the Minecraft Wiki
- [CloudburstMC](https://github.com/CloudburstMC) for the [NBT library](https://github.com/CloudburstMC/NBT) and the [game data](https://github.com/CloudburstMC/Data) Kore reads

## 📄 License

Kore is released under the [MIT License](LICENSE).

<sub>Kore is not an official Minecraft product and is not approved by or associated with Mojang Studios or Microsoft.</sub>

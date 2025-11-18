# Aggregator Subscription Proxy

A lightweight reverse proxy for the Unicity aggregator service. It guards access to protected endpoints by requiring API key authentication and enforces rate limits based on subscription plans.

The proxy is designed to be a transparent layer that forwards all standard HTTP methods and request bodies, stripping any client-identifying API subscription information to ensure anonymity from the upstream aggregator service.

## Workflows

### Authentication and rate limiting

Some aggregator endpoints require authentication. By default, only the `submit_commitment` JSON-RPC method requires authentication. To access authenticated endpoints, users need to pass along an active API key in the HTTP header "X-API-Key" as follows (the API key in the example is "supersecret"):

```http
X-API-Key: supersecret
```

Alternatively, the API key can be sent with the "Authentication" header using the "Bearer" authentication scheme, although this usage is discouraged and may be removed in the future:

```http
Authorization: Bearer supersecret
```

To be usable, the API key must meet the following criteria:

* It must exist in the database;
* Its status must be 'active';
* It must have an associated pricing plan;
* Its 'active_until' date for its payment plan must not have been exceeded;
* Its rate limits must not have been exceeded.

Every pricing plan specifies the following two rate limits for each API key: 1) per second, and 2) per day. All authenticated endpoints share the counters, but multiple instances of the proxy do not.

Note that for performance reasons the API key information in the database is also cached in the proxy for up to 60 seconds; this means that some changes in database may not be picked up immediately. However, when the changes are made through the user interface, the application usually automatically refreshes the cache as well, but the cache refreshing has not been implemented across multiple instances of the proxy.

If the correct API key is NOT used, the server responds with the HTTP status code 401 (Unauthorized) and does not forward the request.

If requests for a given API key exceed the count defined in the pricing plan, the server responds with HTTP status code 429 (Too Many Requests) and does not forward the request.

The pricing plans as well as API key properties can be changed in the administrative interface.

### Payment flow

API keys can be paid for using the Unicity token. Different payment plans can have different costs, which can be set using the Admin Interface.

When a user purchases a payment plan, it always lasts for 30 days from the time of payment completion, regardless of any previously active plan. The most recently paid plan becomes the active plan. If the previous plan was still active at the time of the new purchase, the user gets a discount on the new plan equivalent to the cost of the unused portion of the previous plan. This discount is calculated based on the fraction of the 30-day period that remains unused due to the plan change (measured from 15 minutes after payment initiation). The discount uses the current expiry time and current price of the previous pricing plan. (If pricing has increased over time, this approach benefits the customer by providing a larger discount.) If the calculated discount would reduce the payment below a minimum threshold (1000 units at the time of this writing) or make it negative, the user still pays the minimum amount.

Note: The license duration is calculated as a fixed number of milliseconds (30 × 24 × 60 × 60 × 1000), which may not correspond to exactly 30 calendar days in all time zones due to daylight saving time transitions, leap seconds and so forth.

The following shows the RESTful API for requesting for new API keys and paying for them, as well as paying for existing API keys. This interface is meant to be used by user-facing software such as cryptocurrency wallets.

The user can take a look at the available pricing plans using following request.

**Request:**

`GET /api/payment/plans`

**Response:**
```JSON
{
  "availablePlans": [
    {
      "planId": 1,
      "name": "basic",
      "requestsPerSecond": 5,
      "requestsPerDay": 10000,
      "price": "1000000"
    },
    {
      "planId": 2,
      "name": "standard",
      "requestsPerSecond": 10,
      "requestsPerDay": 100000,
      "price": "5000000"
    },
    {
      "planId": 3,
      "name": "premium",
      "requestsPerSecond": 20,
      "requestsPerDay": 500000,
      "price": "10000000"
    },
    {
      "planId": 4,
      "name": "enterprise",
      "requestsPerSecond": 50,
      "requestsPerDay": 1000000,
      "price": "50000000"
    }
  ]
}
```

The returned list above includes the current list of available pricing plans.

Next, the user initiates payment for their API key. The user can either supply an existing API key in the `apiKey` field, or the user can leave the field empty, in which case a new API key will be created for the user. Additionally, the user specifies the chosen payment plan ID.

If the user does not complete the payment flow in about 15 minutes then the flow expires automatically and if the user wishes to continue then the user must start the flow again from the payment initiation endpoint here. The endpoint must also be invoked again if the user wishes to change any of the parameters specified here.

**Request:**

`POST /api/payment/initiate`

```JSON
{
  "apiKey": "sk_a70c32027c2246aa8dcdac178e79df41",
  "targetPlanId": 3
}
```

**Response:**
```JSON
{
  "sessionId": "2c17b7a1-5e8c-4dd3-9679-4eb076033355",
  "paymentAddress": "DIRECT://0000399bd25b5a4315e8689b943c07ca1c67ad264eb3086f282a3a888534669c24f11fddd789",
  "price": "10000000",
  "acceptedCoinId": "455ad8720656b08e8dbd5bac1f3c73eeea5431565f6c1c3af742b1aa12d41d89",
  "expiresAt": "2025-10-01T11:15:22.095882Z"
}
```

In the response, the server has responded with the address where the payment should be sent, the price for the purchase and the accepted coin ID. The "expiresAt" field specifies the current payment session end time, not the subscription end time.

After that, the user sends the transfer commitment data as a JSON object, as well as the token contents.

In the same payment session, the user can only pay with one token which must contain exactly the right amount of the right coins and no other coins.

If the user invokes this endpoint twice in a row (for example, when the first invocation timed out), the user must use the same token the next time as well (otherwise, the user must invoke the payment initiation endpoint to restart the flow).

Note that the server stores the request input data in the `payment_sessions` table (committed in a separate database transaction than the rest of the endpoint execution), so that even if a payment fails, the table still contains the `request_id` field and the token that the user sent. This allows the server administrator to query whether the token was successfully aggregated into the Unicity blockchain (therefore received by her) irrespective of whether the payment session as a whole failed for some reason; and if the payment was indeed aggregated but the payment failed on the server side (in other words, if she did receive the payment but the user did not get the corresponding payment plan), she can fix the situation in the administrative interface manually; she can also construct the received token manually.

**Request:**

`POST /api/payment/complete`

```JSON
{
  "sessionId": "2c17b7a1-5e8c-4dd3-9679-4eb076033355",
  "salt": "zhQQmaGHH21tVsSZ6N/aZrkRH1MzLy0i2ukDfPWEDYI=",
  "transferCommitmentJson": "{\"requestId\":\"000010ea54a06fb2ab60515118459f348ddd0da7d6a671162f3400349787b8775c9a\",\"transactionData\":{\"dataHash\":null,\"message\":null,\"recipient\":\"DIRECT://0000399bd25b5a4315e8689b943c07ca1c67ad264eb3086f282a3a888534669c24f11fddd789\",\"salt\":\"ce141099a1871f6d6d56c499e8dfda66b9111f53332f2d22dae9037cf5840d82\",\"state\":{\"unlockPredicate\":[0,\"01\",\"865820e729e16b699edd854853a69db9e7fb321dee7e87a356d909a2897548dbfe96e443030e0f5821020c28d70fce18d7d9e8311b806be738c596b70aa2bf86159f29514bbde934ff3e69736563703235366b316653484132353658208633b2866ed8eb8550961be7e4003b8558ced02454bfea3e9250da1741a2e25c\"],\"data\":null},\"nametags\":[]},\"authenticator\":{\"algorithm\":\"secp256k1\",\"publicKey\":\"020c28d70fce18d7d9e8311b806be738c596b70aa2bf86159f29514bbde934ff3e\",\"signature\":\"ee937796755757a11b86ff13e935c534236eb18b5ea2fbf29417afe6abcb6d94374ebee291884e743dbcd86f5ef1e178a982704e013b6b37f995dea25fda99f201\",\"stateHash\":\"000088f2b1fb225dcf0728232956c8cde50c5c7785d0507e0533a084ba4d49614914\"}}",
  "sourceTokenJson": "{\"version\":\"2.0\",\"state\":{\"unlockPredicate\":[0,\"01\",\"865820e729e16b699edd854853a69db9e7fb321dee7e87a356d909a2897548dbfe96e443030e0f5821020c28d70fce18d7d9e8311b806be738c596b70aa2bf86159f29514bbde934ff3e69736563703235366b316653484132353658208633b2866ed8eb8550961be7e4003b8558ced02454bfea3e9250da1741a2e25c\"],\"data\":null},\"genesis\":{\"data\":{\"tokenId\":\"e729e16b699edd854853a69db9e7fb321dee7e87a356d909a2897548dbfe96e4\",\"tokenType\":\"030e0f\",\"tokenData\":\"\",\"coins\":[[\"8d42dbbb70c91c69ae43dec976cc76c1cfc15b2bcbffb3c1197b0a2838a34d4d\",\"10000000\"]],\"recipient\":\"DIRECT://0000300903785855cc02575ade907822421fcfad2b3372b3c1976bee97f07e3a152e2faefbab\",\"salt\":\"42ccf532301257a181045eb458085201d5caef528bcd54f1594994c57c8257f4\",\"dataHash\":null,\"reason\":null},\"inclusionProof\":{\"merkleTreePath\":{\"root\":\"00002f2d093f1cd4af5c9bc6016db38f0219e425debe99c4e471aea2f78be72d1861\",\"steps\":[{\"path\":\"57896165435950272843767692211320132151909989899270994914181710635169875542722\",\"sibling\":[\"9f3f90a262646d26888f00639967b549fe74faa38d9446f9733a5b11fbdd7879\"],\"branch\":[\"0000255277463c877ad1e376393790bb1a597cf91ba990025a32ff28c969e9928968\"]},{\"path\":\"14\",\"sibling\":[\"cbbaf59e35bcecbd56e4379e639d024b98a92ba389247cf80514f1416de693fe\"],\"branch\":[null]},{\"path\":\"2\",\"sibling\":[\"7c639111f9fea9c58e8d6822ca8e4526376c6a3037ec1a2f59c06a435fb288ee\"],\"branch\":[null]},{\"path\":\"3\",\"sibling\":[\"6af7dc7fde7c033dccdc8e32180955e4fde40e16a74af46c63397cbcad604d5d\"],\"branch\":[null]},{\"path\":\"3\",\"sibling\":[\"5a7167208aa937bcc39a7808bcf65fe3d6a7b657c7964658021c34a091e1a43a\"],\"branch\":[null]},{\"path\":\"3\",\"sibling\":[\"fa416f37dc049c2d107751c1651124bae8bf1d4c76e7854328dcdf8029e1be2e\"],\"branch\":[null]},{\"path\":\"3\",\"sibling\":[\"da04d9a7ae065828b44a69b59094373963025e2903640058201ecbe4cb44496e\"],\"branch\":[null]},{\"path\":\"3\",\"sibling\":[\"b5cb3479ae329a29a96f2d3ed80496bf514e65bc05c20673f54d2ea5a2a3138a\"],\"branch\":[null]},{\"path\":\"2\",\"sibling\":[\"cf21e816a3aa91310ea4241538c5d3c1f922ab472bc7443004758d496921b33f\"],\"branch\":[null]},{\"path\":\"2\",\"sibling\":[\"c5f3d436a97ccf2339fda0ed1fd5ccca09a2e1e637570742df17971b6b625c4c\"],\"branch\":[null]},{\"path\":\"2\",\"sibling\":[\"4a58b590ed653b68daead1cae08c2a6eb69363497d3e69748e22ef3f3b778472\"],\"branch\":[null]},{\"path\":\"3\",\"sibling\":[\"923ddda307255395f1f908e1e9406bb2f7613f25b4c724404031fe9894bedbd7\"],\"branch\":[null]},{\"path\":\"2\",\"sibling\":[\"aa3d2534160672ce6cbf7aadfee38698bbc11841da965ec414f2f3e046d220d9\"],\"branch\":[null]},{\"path\":\"3\",\"sibling\":[\"67dc99c5687b9560bcf1720943b8d9b40058dda55be5320f818fb1cf288511cb\"],\"branch\":[null]},{\"path\":\"3\",\"sibling\":[\"5721c14f8b4d951f09f283b5ff036dfb4efee3513d50bbbcdf5a402aeba4bf79\"],\"branch\":[null]},{\"path\":\"2\",\"sibling\":[\"5810046d598b1b2ca43dd86cfe6d5fa5a9c45d44b47b986e4df521ea28fef03d\"],\"branch\":[null]}]},\"authenticator\":{\"algorithm\":\"secp256k1\",\"publicKey\":\"02b19b3fe8edb809c8d9e168f82d50ddeda0b52d19d89d8539348f7409b820f2a4\",\"signature\":\"e4e43504200c95ae117dc355c1f0c25cad4c2375995fbc5cb5a14b101a6b48a02dbaa78870ff39525529ec7495494d03874a55373c2388d978443e0de5c04e9c01\",\"stateHash\":\"000075849613225594a68eb7333b4df2dd04c2399020bac6e04fa98f130f9343acb5\"},\"transactionHash\":\"0000f18d22976f66d6ceb59bf06f910b5076bc7097f2703bfc7981837955041d4308\",\"unicityCertificate\":\"d903ef8701d903f08a011a000275c000582200008ed419d7732ddc33070b184cc1d79918e69b86ba0a60fd87f66808ba1082bca9582200002f2d093f1cd4af5c9bc6016db38f0219e425debe99c4e471aea2f78be72d1861401a68dd09c8582200002f2d093f1cd4af5c9bc6016db38f0219e425debe99c4e471aea2f78be72d186100f65820df709ddbd3815a68661519ea722beb43417830d09d22f572513c5d4d3f7c7059582006fb06b1e4a90313b8017f19b5586dbb9e1a6fd50b9701d8f2b3b41efed1c15382418080d903f683010780d903e98801031a000ec30f001a68dd09cb5820b8700a866cf5e58a1e07c652b28d7cf350251a264737583610360c08bcc795995820c5c91f37689dc491fc67a143285464776b4c636d34d3364ecee144721c84df74a1783531365569753248416b795152694137704d677a674c6a39476761424a454a61387a6d7839647a7155446136577851504a38326768555841cd8917fc191ca9a58dbc13748a4065ddaa81598be61a5f6e67a5cb06fd55acc84d29641c653fd319a2582a929cc4ba893fe8feabfcf05d4816ba653788ec74d801\"}},\"transactions\":[],\"nametags\":[]}"
}
```

**Response:**

```JSON
{
  "success": true,
  "message": "Payment verified. New API key created successfully.",
  "newPlanId": 3,
  "apiKey": "sk_a70c32027c2246aa8dcdac178e79df41"
}
```

After the above success message, the key is ready to be used. The key is returned in the `apiKey` field.

Note that if the payment fails, it may need to be manually completed (or refunded) by the server operator(s). For example, it may happen that network goes down in the middle of the payment, or the user could send the wrong amount of tokens.

Information about they key can be accessed any time using the following endpoint:

**Request:**

`GET /api/payment/key/sk_a70c32027c2246aa8dcdac178e79df41
`

**Response:**
```JSON
{
  "status" : "active",
  "expiresAt" : "2025-11-01T11:00:22.096073Z",
  "pricingPlan" : {
    "id": 1,
    "name": "basic",
    "requestsPerSecond": 5,
    "requestsPerDay": 50000,
    "price": "1000000"
  }
}
```

The endpoint also shows the time of expiry for the key.

Note that currently, the payment actives the key for 1 month. If the user pays again during the time the key is active, the key expiration date is further advanced by 1 month.

## Administrative interface

There is an administrative interface, by default available at http://localhost:8080/admin. The password is set either by the `ADMIN_PASSWORD` environment variable or as a configuration setting.

The interface allows to modify API keys, pricing plans and shard configuration.

## Sharding

The subscription proxy routes JSON-RPC messages to aggregators in the correct shards. For that purpose, every JSON-RPC message must contain exactly one of the following parameters:

* `requestId`: this is a standard Request ID parameter that is used for aggregator's JSON-RPC endpoints like `submit_commitment`. It contains the Unicity aggregation tree's Request ID in hex, without the "0x" prefix. The subscription proxy automatically routes the request to the correct shard according to the shard configuration.
* `shardId`: this specifies a Shard ID, a non-negative integer which is assigned also in the shard configuration.

The administrative interface allows modifying the shard configuration as a JSON file. When the configuration is updated in the UI, the changes are propagated to all instances of the subscription proxy within seconds. A sample shard configuration is as follows:

```json
{
  "version": 1,
  "shards": [
    {
      "id": 2,
      "url": "http://host.docker.internal:3001"
    },
    {
      "id": 3,
      "url": "http://host.docker.internal:3002"
    }
  ]
}
```

The above shard configuration declares 2 shards. Specifically:

* Each shard has an identifier declared (2 and 3, respectively). This identifier is used in 2 ways:
    * For JSON-RPC requests that contain a `shardId` parameter, that parameter value is matched exactly against a shard identifier in the configuration, naturally indicating a shard that the request must be proxied to.
    * For JSON-RPC requests that contain a `requestId` parameter, the shard identifier here is matched in the following way. The Shard ID works as a binary suffix for Request ID values -- that is, the Request ID must "end with" (its least significant bits should equal) the shard identifier of given shard, except for the first bit of the shard identifier that is always set to '1'. The reason that the Shard ID is prefixed by a binary digit '1' is to allow for encoding leading zeroes. Shard ID values are written in decimal. For example, to match Request IDs that end with two binary zeroes (00), the Shard ID would be 100 in binary, which is 4 in decimal, thus the Shard ID would be written as 4. Note that if there is only one shard, its identifier must be 1 which represents an empty Request ID suffix (as there are no bits left in the binary digit after removing the first binary digit). For more examples of Shard IDs, refer to the example tables below.
* Each shard has a corresponding aggregator URL specified. All requests that are matched against the given shard are proxied to that URL.

All requests that are not detected as JSON-RPC requests are proxied to a random shard's URL for load balancing purposes. If needed, cookies can be used to create a "sticky shard" (the names of the cookies are `UNICITY_SHARD_ID` and `UNICITY_REQUEST_ID`; their values are formatted the same way as the JSON-RPC parameters `requestId` and `shardId`).

The following examples demonstrate the Shard ID numbering scheme.

If there is only one shard in the system, its ID must be "1":

Shard ID | Binary   | Suffix Pattern | Matches Request IDs ending with
---------|----------|----------------|--------------------------------
1        | 1        | (empty)        | All IDs (single shard)

If there are 2 shards, they must have the following IDs:

Shard ID | Binary   | Suffix Pattern | Matches Request IDs ending with
---------|----------|----------------|--------------------------------
2        | 10       | 0              | ...0
3        | 11       | 1              | ...1

As a final example, a configuration with 4 shards must have the following IDs:

Shard ID | Binary   | Suffix Pattern | Matches Request IDs ending with
---------|----------|----------------|--------------------------------
4        | 100      | 00             | ...00
5        | 101      | 01             | ...01
6        | 110      | 10             | ...10
7        | 111      | 11             | ...11

## Configuration settings

The command line parameter `--help` prints out various configuration options.

## Prerequisites

- Java 21 or later
- Gradle 8.x (wrapper included)
- Aggregator service running (default: http://localhost:3000)

## Quick Start

### Build and Run

```bash
# Build the project
./gradlew build

# Start a local database instance in Docker
docker run -d -p 5432:5432 \
    -e POSTGRES_DB=aggregator \
    -e POSTGRES_USER=postgres \
    -e POSTGRES_PASSWORD=postgres \
    --name postgres-aggregator \
    postgres:15-alpine

# Start proxying towards the test network aggregator
DB_URL=jdbc:postgresql://localhost:5432/aggregator \
  DB_USER=postgres \
  DB_PASSWORD=postgres \
  SERVER_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef \
  ./gradlew run"
```

### Docker Compose with Load Balancing

The project includes a sample Docker Compose configuration with HAProxy load balancing across 3 proxy nodes.

The `.env.sample` file contains the environment variables to declare.

The following is a quick way to start and use the Docker containers.

```bash
# Build images
docker compose build --no-cache

# Start all services (1 HAProxy + 3 proxy nodes + 1 database)
docker compose up -d

# View logs from all services
docker compose logs -f

# View logs from a specific node
docker compose logs -f proxy-1

# Check service status
docker compose ps
```

By default, the service assumes a single shard and a single aggregator available at http://localhost:3000. To change this sharding configuration you can use the Admin UI.

Here are the key URLs:

- Admin UI: http://localhost:8080/admin.
- HAProxy stats page: http://localhost:8404/stats.
- Incoming requests are proxied from here: http://localhost:8080/.

#### Architecture

- **HAProxy**: Load balancer distributing traffic across proxy nodes
  - Round-robin load balancing for API requests
  - Cookie-based sticky sessions for admin UI (preserves login state)
  - Health checks on all backend nodes
  - Exposed on port 8080 (configurable via `PROXY_PORT`)
  - Stats page on port 8404 (configurable via `HAPROXY_STATS_PORT`)

- **3 Proxy Nodes** (proxy-1, proxy-2, proxy-3):
  - Each node runs the same proxy application
  - Shared PostgreSQL database for API keys, pricing plans, and payments
  - Independent in-memory rate limiting per node (total capacity = limit × 3)
  - Independent API key caches (60s TTL)
  - Separate log volumes for each node

- **PostgreSQL**: Single shared database instance

#### Configuration

Environment variables can be configured in a `.env` file:

```bash
# Proxy port (HAProxy frontend)
PROXY_PORT=8080

# HAProxy stats page port
HAPROXY_STATS_PORT=8404

# Target aggregator URL
TARGET_URL=https://goggregator-test.unicity.network

# Admin password for UI
ADMIN_PASSWORD=your-secure-password

# Server secret (hex string, even length)
SERVER_SECRET=your-64-char-hex-string

# Database password
POSTGRES_PASSWORD=aggregator

# Database port
POSTGRES_PORT=5432

# Logging
LOG_LEVEL=INFO
```

## Development

Run tests, including integration tests using a local aggregator at http://localhost:3000.
```bash
export AGGREGATOR_URL="http://localhost:3000" && ./gradlew clean test

```
To run within an IDE, use the main class ```org.unicitylabs.proxy.Main```.

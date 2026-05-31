# Architecture Overview

## Components

| Layer            | Technology                       | Purpose                                  |
| ---------------- | -------------------------------- | ---------------------------------------- |
| Backend          | Spring Boot 3.3 / Java 21        | REST API, KHQR generation, bank calls    |
| Database         | PostgreSQL 16 / Oracle 19c-23ai  | Users, keys, merchants, transactions     |
| Cache            | Redis 7                          | Rate limit + idempotency keys            |
| Java SDK         | java.net.http + Jackson          | Drop-in client for Java apps             |
| Python SDK       | requests + pydantic              | Drop-in client for Python apps           |
| Dashboard        | React 18 + Vite + Tailwind CSS   | Developer UI / merchant management       |
| Deploy           | Docker + Nginx                   | Production deployment                    |

## End-to-end payment flow

1. **Developer signup** → JWT issued for dashboard.
2. **Subscribe to plan** → KhmerBank sends a KHQR with the plan price; dev pays. Once webhook confirms, plan activates.
3. **Generate API key** → returned ONCE, stored as SHA-256 hash.
4. **Link merchant** → developer adds ABA / Wing / Bakong details (encrypted with AES-GCM at rest).
5. **Generate QR (`POST /payments/qr`)** → backend builds EMV TLV string, computes CRC16, renders PNG (ZXing), persists `QrCode` row.
6. **Customer scans + pays** at their bank app.
7. **Bank webhook** (or scheduled poll for Bakong) → backend marks `QrCode` PAID and forwards a signed webhook to the developer's URL.
8. **Developer queries `/payments/{id}/status`** any time, or relies on webhook.

## KHQR EMV TLV layout

| Tag | Description                       |
| --- | --------------------------------- |
| 00  | Payload Format Indicator (`01`)   |
| 01  | Point of Initiation (`12` dynamic)|
| 29  | Bakong Merchant Account Info      |
| 30  | ABA Merchant Account Info         |
| 31  | Wing Merchant Account Info        |
| 52  | Merchant Category Code            |
| 53  | Currency (`116` KHR / `840` USD)  |
| 54  | Transaction Amount                |
| 58  | Country Code (`KH`)               |
| 59  | Merchant Name                     |
| 60  | Merchant City                     |
| 62  | Additional Data (reference, bill) |
| 63  | CRC-16/CCITT-FALSE                |

## Module diagram

```
controller/  ──►  service/  ──►  bank/aba|wing|bakong
                          ╲          ╱
                           qrcode/KhqrGenerator + ImageRenderer
                          ╱          ╲
repository/  ──►  Postgres            scheduler/PaymentStatusPoller
```

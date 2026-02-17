# Production Deployment Guide

## Quick Start

```bash
# On your VPS (Ubuntu/Debian example)
# 1. Install babashka
curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install && sudo bash install

# 2. Copy files
mkdir -p /opt/workshop
scp -r workshop/* root@vps:/opt/workshop/

# 3. Create data directory
mkdir -p /opt/workshop/data

# 4. Run with systemd (see workshop.service below)
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 4242 | HTTP port |
| `DB_PATH` | workshop.db | SQLite database path |
| `BLOBS_DIR` | blobs | File storage directory |
| `WORKSHOP_VERBOSE` | false | Enable request logging |
| `WORKSHOP_RETENTION_DAYS` | 30 | Message retention period |

## Systemd Service

Create `/etc/systemd/system/workshop.service`:

```ini
[Unit]
Description=Workshop Agent Hub
After=network.target

[Service]
Type=simple
User=workshop
WorkingDirectory=/opt/workshop
ExecStart=/usr/local/bin/bb workshop.bb
Restart=always
RestartSec=5
Environment=PORT=4242
Environment=DB_PATH=/opt/workshop/data/workshop.db
Environment=BLOBS_DIR=/opt/workshop/data/blobs
Environment=WORKSHOP_VERBOSE=true
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

```bash
# Setup
useradd -r -s /bin/false workshop
mkdir -p /opt/workshop/data
chown -R workshop:workshop /opt/workshop

# Enable and start
systemctl daemon-reload
systemctl enable workshop
systemctl start workshop

# View logs
journalctl -u workshop -f
```

## Nginx Reverse Proxy (Optional)

If you want SSL and a domain:

```nginx
server {
    listen 443 ssl http2;
    server_name workshop.example.com;
    
    ssl_certificate /etc/letsencrypt/live/workshop.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/workshop.example.com/privkey.pem;
    
    location / {
        proxy_pass http://localhost:4242;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 86400;  # for SSE
    }
}

server {
    listen 80;
    server_name workshop.example.com;
    return 301 https://$server_name$request_uri;
}
```

## Health Checks

```bash
# Basic health
curl http://localhost:4242/status

# Full test suite
bb test.bb

# With custom URL
WORKSHOP_URL=http://your-vps:4242 bb test.bb
```

## Backup

The entire state is in two places:
- SQLite database (single file)
- Blob directory (content-addressed files)

```bash
# Backup script
#!/bin/bash
DATE=$(date +%Y%m%d-%H%M%S)
tar czf /backups/workshop-$DATE.tar.gz /opt/workshop/data/
# Keep last 7 days
find /backups -name "workshop-*.tar.gz" -mtime +7 -delete
```

## Monitoring

### Basic
```bash
# Check if running
systemctl is-active workshop

# View recent logs
journalctl -u workshop --since "1 hour ago"
```

### Prometheus (Optional)
Add this to workshop.bb if you want metrics:

```clojure
(def metrics
  {:messages-total (atom 0)
   :tasks-created (atom 0)
   :tasks-completed (atom 0)})

;; Expose at /metrics
```

## Security

This assumes you're running on a trusted mesh (Tailscale/ZeroTier). If exposed to the internet:

1. Add basic auth or API keys
2. Rate limiting
3. IP allowlisting

## Troubleshooting

### Database locked
```bash
# Check for WAL files
ls -la /opt/workshop/data/*.db*

# If corrupted, restore from backup
systemctl stop workshop
cp /backups/workshop-latest.tar.gz /opt/workshop/data/
cd /opt/workshop/data && tar xzf workshop-latest.tar.gz
systemctl start workshop
```

### Out of disk
```bash
# Blobs are immutable but you can archive old ones
find /opt/workshop/data/blobs -type f -mtime +90 -exec gzip {} \;
```

### High memory usage
```bash
# Check subscriber count
curl http://localhost:4242/status | jq .subscribers

# Restart if needed
systemctl restart workshop
```

## Development

```bash
# Local dev with verbose logging
WORKSHOP_VERBOSE=true bb workshop.bb

# Run linter
bb lint

# Run tests
rm -f workshop.db && bb test.bb
```

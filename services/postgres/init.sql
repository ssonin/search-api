-- Grant replication privilege to the application user so Debezium can connect
-- via the logical replication protocol and stream WAL changes.
ALTER USER search_api REPLICATION;

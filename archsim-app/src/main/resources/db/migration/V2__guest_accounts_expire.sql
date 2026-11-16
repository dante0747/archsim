-- Guests (ADR-0079): an account with an expiry is a visitor's who tried ArchSim in a sandbox
-- without signing in. It has no identity or email; once it expires, the API deletes it with its
-- sessions, its sandbox and everything in the sandbox. Everyone else's account never expires.
ALTER TABLE users ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX ix_users_expires_at ON users (expires_at) WHERE expires_at IS NOT NULL;

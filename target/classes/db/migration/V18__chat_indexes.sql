CREATE INDEX IF NOT EXISTS chat_participants_idx ON conversations(user_id,service_provider_id,status);
CREATE INDEX IF NOT EXISTS chat_messages_order_idx ON messages(conversation_id,created_at,id);
CREATE INDEX IF NOT EXISTS chat_messages_unread_idx ON messages(conversation_id,sender_id) WHERE is_read=false;

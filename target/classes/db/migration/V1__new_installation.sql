-- New installation baseline reconstructed from types.ts. Not an export of the live database.

CREATE TABLE app_users (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), email text NOT NULL UNIQUE, password_hash text NOT NULL, role text NOT NULL DEFAULT 'USER' CHECK(role IN ('USER','ADMIN')), token_version integer NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now());

CREATE TABLE "admin_settings" (
  "created_at" timestamptz DEFAULT now(),
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "settings" jsonb,
  "type" text,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "clip_comments" (
  "clip_id" uuid,
  "comment" text,
  "created_at" timestamptz DEFAULT now(),
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "updated_at" timestamptz DEFAULT now(),
  "user_id" uuid
);

CREATE TABLE "clip_likes" (
  "clip_id" uuid,
  "created_at" timestamptz DEFAULT now(),
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "user_id" uuid
);

CREATE TABLE "conversations" (
  "created_at" timestamptz DEFAULT now(),
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "last_message_at" timestamptz,
  "service_provider_id" uuid,
  "status" text DEFAULT 'active',
  "updated_at" timestamptz DEFAULT now(),
  "user_id" uuid
);

CREATE TABLE "dynamic_banners" (
  "alt_text" text,
  "button_text" text,
  "created_at" timestamptz DEFAULT now(),
  "description" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "image_url" text,
  "is_active" boolean DEFAULT true,
  "link_url" text,
  "order_index" numeric DEFAULT 0,
  "target_blank" boolean DEFAULT false,
  "title" text,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "dynamic_categories" (
  "created_at" timestamptz DEFAULT now(),
  "icon_name" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "is_active" boolean DEFAULT true,
  "label" text,
  "order_index" numeric DEFAULT 0,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "dynamic_category_chips" (
  "chip_id" text,
  "created_at" timestamptz DEFAULT now(),
  "icon_name" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "is_active" boolean DEFAULT true,
  "label" text,
  "order_index" numeric DEFAULT 0,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "dynamic_clips" (
  "author_avatar" text,
  "author_name" text,
  "category" text,
  "comments" numeric DEFAULT 0,
  "created_at" timestamptz DEFAULT now(),
  "description" text,
  "duration" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "is_active" boolean DEFAULT true,
  "likes" numeric DEFAULT 0,
  "linked_product_id" uuid,
  "linked_product_section" text,
  "linked_product_type" text,
  "order_index" numeric DEFAULT 0,
  "thumbnail_url" text,
  "title" text,
  "updated_at" timestamptz DEFAULT now(),
  "video_url" text
);

CREATE TABLE "dynamic_news_banners" (
  "alt_text" text,
  "created_at" timestamptz DEFAULT now(),
  "description" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "image_url" text,
  "is_active" boolean DEFAULT true,
  "order_index" numeric DEFAULT 0,
  "title" text,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "dynamic_popular_categories" (
  "banner_image_url" text,
  "banner_subtitle" text,
  "banner_title" text,
  "created_at" timestamptz DEFAULT now(),
  "filter_chips" jsonb,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "image_url" text,
  "is_active" boolean DEFAULT true,
  "name" text,
  "order_index" numeric DEFAULT 0,
  "store" text,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "dynamic_product_detail_banners" (
  "alt_text" text,
  "created_at" timestamptz DEFAULT now(),
  "description" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "image_url" text,
  "is_active" boolean DEFAULT true,
  "order_index" numeric DEFAULT 0,
  "title" text,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "dynamic_products" (
  "category" text,
  "created_at" timestamptz DEFAULT now(),
  "description" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "image_url" text,
  "is_active" boolean DEFAULT true,
  "is_essential" boolean DEFAULT false,
  "is_featured" boolean DEFAULT false,
  "is_offer" boolean DEFAULT false,
  "is_top_choice" boolean DEFAULT false,
  "name" text,
  "order_index" numeric DEFAULT 0,
  "price" text,
  "product_group" text,
  "rating" numeric,
  "source_product_id" uuid,
  "type" text,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "dynamic_promotional_banners" (
  "alt_text" text,
  "created_at" timestamptz DEFAULT now(),
  "description" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "image_url" text,
  "is_active" boolean DEFAULT true,
  "order_index" numeric DEFAULT 0,
  "title" text,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "dynamic_rentals" (
  "available" boolean DEFAULT true,
  "category" text,
  "created_at" timestamptz DEFAULT now(),
  "description" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "image_url" text,
  "is_active" boolean DEFAULT true,
  "location" text,
  "name" text,
  "order_index" numeric DEFAULT 0,
  "period" text,
  "popular" boolean DEFAULT false,
  "price" text,
  "rating" numeric,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "dynamic_store_data" (
  "address" text,
  "contact_email" text,
  "contact_phone" text,
  "created_at" timestamptz DEFAULT now(),
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "is_active" boolean DEFAULT true,
  "store_description" text,
  "store_logo" text,
  "store_name" text,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "mercado_pago_tokens" (
  "access_token" text,
  "created_at" timestamptz DEFAULT now(),
  "expires_at" timestamptz,
  "expires_in" numeric,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "lojista_id" uuid,
  "public_key" text,
  "refresh_token" text,
  "updated_at" timestamptz DEFAULT now(),
  "user_id_mp" text
);

CREATE TABLE "mercado_pago_transactions" (
  "approved_at" timestamptz,
  "card_brand" text,
  "card_last_four_digits" text,
  "created_at" timestamptz DEFAULT now(),
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "installments" numeric,
  "mp_payment_id" text,
  "mp_response" jsonb,
  "mp_status" text,
  "mp_status_detail" text,
  "net_amount" numeric,
  "order_id" uuid,
  "payment_method_id" text,
  "payment_type_id" text,
  "qr_code" text,
  "qr_code_base64" text,
  "refunded_at" timestamptz,
  "transaction_amount" numeric,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "messages" (
  "content" text,
  "conversation_id" uuid,
  "created_at" timestamptz DEFAULT now(),
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "is_read" boolean DEFAULT false,
  "message_type" text DEFAULT 'text',
  "sender_id" uuid,
  "sender_type" text
);

CREATE TABLE "notifications" (
  "created_at" timestamptz DEFAULT now(),
  "data" jsonb,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "is_read" boolean DEFAULT false,
  "message" text,
  "title" text,
  "type" text,
  "user_id" uuid
);

CREATE TABLE "orders" (
  "buyer_id" uuid,
  "cancelled_at" timestamptz,
  "created_at" timestamptz DEFAULT now(),
  "delivery_address" jsonb,
  "delivery_type" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "marketplace_fee" numeric,
  "metadata" jsonb,
  "order_type" text,
  "paid_at" timestamptz,
  "payment_method" text,
  "product_id" uuid,
  "rental_id" uuid,
  "rental_info" jsonb,
  "seller_amount" numeric,
  "seller_id" uuid,
  "shipping_cost" numeric,
  "status" text,
  "total_amount" numeric,
  "updated_at" timestamptz DEFAULT now()
);

CREATE TABLE "platform_settings" (
  "commission_rate" numeric,
  "created_at" timestamptz DEFAULT now(),
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "updated_at" timestamptz DEFAULT now(),
  "updated_by" text
);

CREATE TABLE "product_images" (
  "created_at" timestamptz DEFAULT now(),
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "image_path" text,
  "image_url" text,
  "is_primary" boolean DEFAULT false,
  "product_id" uuid
);

CREATE TABLE "products" (
  "admin_comments" text,
  "approval_date" timestamptz,
  "approval_status" text DEFAULT 'pending',
  "category" text,
  "condition" text,
  "created_at" timestamptz DEFAULT now(),
  "description" text,
  "expires_at" timestamptz,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "images" text[],
  "is_featured" boolean DEFAULT false,
  "location" text,
  "manufacturing_date" text,
  "price" numeric,
  "product_type" text,
  "quantity" numeric DEFAULT 1,
  "seller_info" jsonb,
  "seller_type" text,
  "status" text DEFAULT 'active',
  "title" text,
  "unit" text,
  "updated_at" timestamptz DEFAULT now(),
  "user_id" uuid
);

CREATE TABLE "push_subscriptions" (
  "auth" text,
  "created_at" timestamptz DEFAULT now(),
  "endpoint" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "p256dh" text,
  "platform" text,
  "updated_at" timestamptz DEFAULT now(),
  "user_id" uuid
);

CREATE TABLE "rental_inquiries" (
  "admin_notes" text,
  "city" text,
  "created_at" timestamptz DEFAULT now(),
  "daily_rate" numeric,
  "deposit_amount" numeric,
  "description" text,
  "equipment_type" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "name" text,
  "payment_method" text,
  "payment_status" text,
  "phone" text,
  "pix_code" text,
  "pix_qr_code" text,
  "rental_days" numeric,
  "rental_item_id" uuid,
  "rental_item_name" text,
  "status" text,
  "total_amount" numeric,
  "updated_at" timestamptz DEFAULT now(),
  "user_id" uuid,
  "whatsapp_contact" text
);

CREATE TABLE "seller_stores" (
  "address" text,
  "business_name" text,
  "city" text,
  "created_at" timestamptz DEFAULT now(),
  "description" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "logo_url" text,
  "phone" text,
  "state" text,
  "updated_at" timestamptz DEFAULT now(),
  "user_id" uuid
);

CREATE TABLE "service_providers" (
  "address" text,
  "category" text,
  "city" text,
  "created_at" timestamptz DEFAULT now(),
  "daily_rate" numeric,
  "description" text,
  "documents" jsonb,
  "email" text,
  "experience" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "name" text,
  "neighborhood" text,
  "phone" text,
  "specialties" text,
  "status" text DEFAULT 'pending',
  "updated_at" timestamptz DEFAULT now(),
  "user_id" uuid
);

CREATE TABLE "user_addresses" (
  "city" text,
  "complement" text,
  "created_at" timestamptz DEFAULT now(),
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "is_default" boolean DEFAULT false,
  "label" text,
  "neighborhood" text,
  "number" text,
  "state" text,
  "street" text,
  "type" text,
  "updated_at" timestamptz DEFAULT now(),
  "user_id" uuid,
  "zip_code" text
);

CREATE TABLE "user_payment_methods" (
  "card_brand" text,
  "card_expiry_month" text,
  "card_expiry_year" text,
  "card_holder_name" text,
  "card_last_four" text,
  "created_at" timestamptz DEFAULT now(),
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "is_active" boolean DEFAULT true,
  "is_default" boolean DEFAULT false,
  "mp_card_token" text,
  "mp_customer_id" text,
  "payment_type" text,
  "pix_key" text,
  "pix_key_type" text,
  "updated_at" timestamptz DEFAULT now(),
  "user_id" uuid
);

CREATE TABLE "user_profiles" (
  "avatar_url" text,
  "created_at" timestamptz DEFAULT now(),
  "email" text,
  "full_name" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "is_verified" boolean DEFAULT false,
  "phone" text,
  "updated_at" timestamptz DEFAULT now(),
  "user_type" text DEFAULT 'customer'
);

CREATE TABLE "user_roles" (
  "granted_at" timestamptz,
  "granted_by" text,
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  "role" text,
  "user_id" uuid
);

ALTER TABLE user_profiles ADD CONSTRAINT profiles_user_fk FOREIGN KEY(id) REFERENCES app_users(id) ON DELETE CASCADE;

CREATE UNIQUE INDEX addresses_one_default ON user_addresses(user_id) WHERE is_default;

CREATE UNIQUE INDEX stores_one_per_user ON seller_stores(user_id);

CREATE UNIQUE INDEX likes_one_per_clip ON clip_likes(user_id,clip_id);

ALTER TABLE products ADD CONSTRAINT positive_price CHECK (price >= 0), ADD CONSTRAINT positive_quantity CHECK (quantity >= 0);

ALTER TABLE user_profiles ADD CONSTRAINT profile_type CHECK(user_type IN ('customer','service_provider','store_owner','rental_company'));

CREATE TABLE site_content (section text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now());

CREATE INDEX "clip_comments_owner_idx" ON "clip_comments" (user_id);

CREATE INDEX "clip_likes_owner_idx" ON "clip_likes" (user_id);

CREATE INDEX "conversations_owner_idx" ON "conversations" (user_id);

CREATE INDEX "notifications_owner_idx" ON "notifications" (user_id);

CREATE INDEX "products_owner_idx" ON "products" (user_id);

CREATE INDEX "push_subscriptions_owner_idx" ON "push_subscriptions" (user_id);

CREATE INDEX "rental_inquiries_owner_idx" ON "rental_inquiries" (user_id);

CREATE INDEX "seller_stores_owner_idx" ON "seller_stores" (user_id);

CREATE INDEX "service_providers_owner_idx" ON "service_providers" (user_id);

CREATE INDEX "user_addresses_owner_idx" ON "user_addresses" (user_id);

CREATE INDEX "user_payment_methods_owner_idx" ON "user_payment_methods" (user_id);

CREATE INDEX "user_roles_owner_idx" ON "user_roles" (user_id);

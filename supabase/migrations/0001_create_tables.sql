-- Migration 0001: create core tables for ProjectCourseV1
-- Run this on your Supabase project (SQL Editor or supabase CLI)

CREATE TABLE IF NOT EXISTS public.app_documents (
  id bigserial PRIMARY KEY,
  table_name text NOT NULL,
  entity_id text NOT NULL,
  data jsonb NOT NULL,
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_app_documents_table_entity ON public.app_documents(table_name, entity_id);

-- Core tables (basic schemas inferred from Room entities)
CREATE TABLE IF NOT EXISTS public.usuarios (
  id bigserial PRIMARY KEY,
  persona_id bigint,
  usuario text,
  password text,
  rol text,
  created_at timestamptz DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.personas (
  id bigserial PRIMARY KEY,
  nombre text,
  apellido text,
  email text,
  telefono text,
  created_at timestamptz DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.videos (
  id bigserial PRIMARY KEY,
  title text,
  description text,
  remoteUrl text,
  localFilePath text,
  thumbnailUri text,
  created_at timestamptz DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.topics (
  id bigserial PRIMARY KEY,
  courseId bigint,
  title text,
  description text,
  orderIndex integer,
  created_at timestamptz DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.content_items (
  id bigserial PRIMARY KEY,
  topicId bigint,
  type text,
  payload jsonb,
  created_at timestamptz DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.tasks (
  id bigserial PRIMARY KEY,
  topicId bigint,
  title text,
  description text,
  dueDate timestamptz,
  created_at timestamptz DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.subscriptions (
  subscriberUsername text NOT NULL,
  creatorUsername text NOT NULL,
  subscriptionDate timestamptz DEFAULT now(),
  PRIMARY KEY (subscriberUsername, creatorUsername)
);

CREATE TABLE IF NOT EXISTS public.task_submissions (
  id bigserial PRIMARY KEY,
  taskId bigint,
  studentUsername text,
  submissionData jsonb,
  created_at timestamptz DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.courses (
  id bigserial PRIMARY KEY,
  title text,
  description text,
  created_at timestamptz DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.roles (
  id bigserial PRIMARY KEY,
  nombre text UNIQUE NOT NULL
);

CREATE TABLE IF NOT EXISTS public.recursos (
  id bigserial PRIMARY KEY,
  nombre text,
  key text,
  position integer
);

CREATE TABLE IF NOT EXISTS public.rol_recursos (
  id bigserial PRIMARY KEY,
  rol_id bigint,
  recurso_id bigint
);

CREATE TABLE IF NOT EXISTS public.chat_messages (
  id bigserial PRIMARY KEY,
  chatId text,
  sender text,
  message text,
  created_at timestamptz DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.file_contexts (
  id bigserial PRIMARY KEY,
  fileName text,
  fileUri text,
  metadata jsonb,
  created_at timestamptz DEFAULT now()
);

-- End of migration 0001

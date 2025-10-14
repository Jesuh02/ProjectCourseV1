-- Supabase / Postgres DDL for personas and usuarios tables
-- Run this in your Supabase SQL editor or psql

-- Table: public.personas
CREATE TABLE IF NOT EXISTS public.personas (
    id bigserial PRIMARY KEY,
    identificacion text NOT NULL,
    nombres text NOT NULL,
    apellidos text NOT NULL,
    email text NOT NULL,
    telefono text NOT NULL,
    direccion text,
    "fechaNacimiento" text,
    avatar text,
    "esUsuario" boolean DEFAULT false,
    created_at timestamptz DEFAULT now()
);

-- Table: public.usuarios
CREATE TABLE IF NOT EXISTS public.usuarios (
    id bigserial PRIMARY KEY,
    usuario text NOT NULL UNIQUE,
    contrasena text NOT NULL,
    persona_id bigint REFERENCES public.personas(id) ON DELETE CASCADE,
    rol_id integer DEFAULT 1,
    created_at timestamptz DEFAULT now()
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_personas_email ON public.personas (email);
CREATE INDEX IF NOT EXISTS idx_usuarios_persona_id ON public.usuarios (persona_id);
CREATE INDEX IF NOT EXISTS idx_usuarios_usuario ON public.usuarios (usuario);

-- Table: public.roles
CREATE TABLE IF NOT EXISTS public.roles (
    id bigserial PRIMARY KEY,
    nombre text NOT NULL UNIQUE,
    nivel real NOT NULL,
    "default" boolean DEFAULT false,
    created_at timestamptz DEFAULT now()
);

-- Table: public.recursos
CREATE TABLE IF NOT EXISTS public.recursos (
    id bigserial PRIMARY KEY,
    nombre text NOT NULL,
    icono text NOT NULL,
    orden integer NOT NULL,
    padre_id bigint REFERENCES public.recursos(id) ON DELETE CASCADE,
    interfaz text,
    created_at timestamptz DEFAULT now()
);

-- Table: public.rol_recursos (many-to-many between roles and recursos)
CREATE TABLE IF NOT EXISTS public.rol_recursos (
    rol_id bigint REFERENCES public.roles(id) ON DELETE CASCADE,
    recurso_id bigint REFERENCES public.recursos(id) ON DELETE CASCADE,
    PRIMARY KEY (rol_id, recurso_id)
);

-- Indexes for roles/recursos
CREATE INDEX IF NOT EXISTS idx_roles_nombre ON public.roles (nombre);
CREATE INDEX IF NOT EXISTS idx_recursos_padre_id ON public.recursos (padre_id);
CREATE INDEX IF NOT EXISTS idx_rol_recursos_rol_id ON public.rol_recursos (rol_id);
CREATE INDEX IF NOT EXISTS idx_rol_recursos_recurso_id ON public.rol_recursos (recurso_id);

-- Table: public.videos
CREATE TABLE IF NOT EXISTS public.videos (
    id bigserial PRIMARY KEY,
    username text NOT NULL,
    description text,
    title text NOT NULL,
    video_uri_string text,
    local_file_path text,
    timestamp bigint,
    remote_id bigint,
    is_paid boolean DEFAULT false,
    thumbnail_uri text,
    price numeric,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_videos_username ON public.videos (username);
CREATE INDEX IF NOT EXISTS idx_videos_title ON public.videos USING gin (to_tsvector('simple', title));

-- Table: public.topics (course topics linked to courses)
-- Note: changed to reference public.courses(id) instead of public.videos(id).
CREATE TABLE IF NOT EXISTS public.topics (
    id bigserial PRIMARY KEY,
    -- store course_id pointing to courses.id
    course_id bigint REFERENCES public.courses(id) ON DELETE CASCADE,
    -- helper column that may be used by an insert/update trigger on the server
    course_title text,
    name text NOT NULL,
    description text,
    order_index integer DEFAULT 0,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_topics_course_id ON public.topics (course_id);
CREATE INDEX IF NOT EXISTS idx_topics_name ON public.topics USING gin (to_tsvector('simple', name));

-- Add a trigger function that will attempt to resolve a provided `course_title`
-- into the corresponding `courses.id` and set `NEW.course_id` accordingly.
-- This is idempotent: it creates or replaces the function and trigger.
CREATE OR REPLACE FUNCTION public.topics_set_course_id_from_title()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- If course_id already provided, keep it. Otherwise, try to resolve by title.
    IF (NEW.course_id IS NULL OR NEW.course_id = 0) AND NEW.course_title IS NOT NULL THEN
        SELECT id INTO NEW.course_id FROM public.courses WHERE title = NEW.course_title LIMIT 1;
    END IF;
    -- Optionally, clear the helper column so it's not stored long-term (comment out to keep)
    -- NEW.course_title := NULL;
    RETURN NEW;
END;
$$;

-- Drop existing trigger if exists, then create a new one
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'topics_set_course_id_trigger') THEN
        PERFORM 1;
    END IF;
END$$;

DROP TRIGGER IF EXISTS topics_set_course_id_trigger ON public.topics;
CREATE TRIGGER topics_set_course_id_trigger
BEFORE INSERT OR UPDATE ON public.topics
FOR EACH ROW EXECUTE FUNCTION public.topics_set_course_id_from_title();

-- If there was previously an accidental FK from content_items -> courses, drop it safely.
-- We don't expect content_items to reference courses in this schema; ensure any stray FK/column is removed.
-- These ALTER statements are idempotent: they will succeed only when the objects exist.
DO $$
BEGIN
    -- If a column named course_id exists on content_items and has a foreign key constraint to courses, drop constraint then column.
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'content_items' AND column_name = 'course_id'
    ) THEN
        -- Try to drop foreign key constraint if present
        BEGIN
            EXECUTE (
                SELECT 'ALTER TABLE public.content_items DROP CONSTRAINT ' || quote_ident(tc.constraint_name)
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY'
                    AND tc.table_schema = 'public'
                    AND tc.table_name = 'content_items'
                    AND kcu.column_name = 'course_id'
                LIMIT 1
            );
        EXCEPTION WHEN OTHERS THEN
            -- ignore if constraint drop fails or doesn't exist
            NULL;
        END;
        -- Drop the column if it still exists
        BEGIN
            EXECUTE 'ALTER TABLE public.content_items DROP COLUMN IF EXISTS course_id';
        EXCEPTION WHEN OTHERS THEN
            NULL;
        END;
    END IF;
END$$;

-- Minimal content_items table (optional - Course may use later)
CREATE TABLE IF NOT EXISTS public.content_items (
    id bigserial PRIMARY KEY,
    topic_id bigint REFERENCES public.topics(id) ON DELETE CASCADE,
    title text NOT NULL,
    body text,
    content_type text,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_content_items_topic_id ON public.content_items (topic_id);

-- Minimal tasks table (optional)
CREATE TABLE IF NOT EXISTS public.tasks (
    id bigserial PRIMARY KEY,
    topic_id bigint REFERENCES public.topics(id) ON DELETE CASCADE,
    title text NOT NULL,
    description text,
    due_date timestamptz,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_tasks_topic_id ON public.tasks (topic_id);

-- Table: public.task_submissions (student submissions for tasks)
CREATE TABLE IF NOT EXISTS public.task_submissions (
    id bigserial PRIMARY KEY,
    task_id bigint REFERENCES public.tasks(id) ON DELETE CASCADE,
    student_username text NOT NULL,
    file_uri text,
    file_name text,
    submission_date bigint,
    grade real,
    feedback text,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_task_submissions_task_id ON public.task_submissions (task_id);
CREATE INDEX IF NOT EXISTS idx_task_submissions_student_username ON public.task_submissions (student_username);

-- Table: public.file_contexts (metadata and extracted content for submitted files)
CREATE TABLE IF NOT EXISTS public.file_contexts (
    id bigserial PRIMARY KEY,
    submission_id bigint REFERENCES public.task_submissions(id) ON DELETE CASCADE,
    file_name text,
    file_type text,
    file_content text,
    extracted_text text,
    metadata text,
    timestamp bigint,
    json_content jsonb,
    content_summary text,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_file_contexts_submission_id ON public.file_contexts (submission_id);

-- Table: public.chat_messages (messages created/received in the in-app chat)
CREATE TABLE IF NOT EXISTS public.chat_messages (
    id bigserial PRIMARY KEY,
    message text NOT NULL,
    is_from_user boolean DEFAULT false,
    timestamp bigint,
    session_id text,
    has_calification boolean DEFAULT false,
    calification_value text,
    calification_added boolean DEFAULT false,
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session_id ON public.chat_messages (session_id);
CREATE INDEX IF NOT EXISTS idx_chat_messages_timestamp ON public.chat_messages (timestamp);

-- Table: public.courses (mirrors Course.kt)
CREATE TABLE IF NOT EXISTS public.courses (
    id bigserial PRIMARY KEY,
    title text NOT NULL,
    description text,
    creator_username text NOT NULL,
    thumbnail_uri text,
    video_uri text,
    local_file_path text,
    duration text,
    category text,
    price numeric DEFAULT 0,
    is_premium boolean DEFAULT false,
    is_published boolean DEFAULT true,
    creation_date text,
    last_modified_date text,
    enrollment_count integer DEFAULT 0,
    rating real DEFAULT 0,
    tags text,
    timestamp bigint DEFAULT (extract(epoch from now())::bigint),
    created_at timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_courses_creator_username ON public.courses (creator_username);
CREATE INDEX IF NOT EXISTS idx_courses_title ON public.courses USING gin (to_tsvector('simple', title));

-- Table: public.subscriptions (users subscribing to creators)
CREATE TABLE IF NOT EXISTS public.subscriptions (
    subscriber_username text NOT NULL,
    creator_username text NOT NULL,
    subscription_date bigint,
    created_at timestamptz DEFAULT now(),
    PRIMARY KEY (subscriber_username, creator_username),
    FOREIGN KEY (subscriber_username) REFERENCES public.usuarios(usuario) ON DELETE CASCADE,
    FOREIGN KEY (creator_username) REFERENCES public.usuarios(usuario) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_subscriber ON public.subscriptions (subscriber_username);
CREATE INDEX IF NOT EXISTS idx_subscriptions_creator ON public.subscriptions (creator_username);

-- WARNING: This schema is for context only and is not meant to be run.
-- Table order and constraints may not be valid for execution.

CREATE TABLE public.chat_messages (
  id bigint NOT NULL DEFAULT nextval('chat_messages_id_seq'::regclass),
  message text NOT NULL,
  is_from_user boolean DEFAULT false,
  timestamp bigint,
  session_id text,
  has_calification boolean DEFAULT false,
  calification_value text,
  calification_added boolean DEFAULT false,
  created_at timestamp with time zone DEFAULT now(),
  usuario_id bigint,
  username text,
  is_typing boolean DEFAULT false,
  is_error boolean DEFAULT false,
  is_graph_response boolean DEFAULT false,
  CONSTRAINT chat_messages_pkey PRIMARY KEY (id),
  CONSTRAINT fk_chat_messages_usuario FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id)
);
CREATE TABLE public.content_items (
  id bigint NOT NULL DEFAULT nextval('content_items_id_seq'::regclass),
  topic_id bigint,
  title text NOT NULL,
  body text,
  content_type text,
  created_at timestamp with time zone DEFAULT now(),
  creator_usuario_id bigint,
  creator_username text,
  order_index integer DEFAULT 0,
  task_id bigint,
  CONSTRAINT content_items_pkey PRIMARY KEY (id),
  CONSTRAINT fk_content_items_task FOREIGN KEY (task_id) REFERENCES public.tasks(id)
);
CREATE TABLE public.courses (
  id bigint NOT NULL DEFAULT nextval('courses_id_seq'::regclass),
  title text NOT NULL,
  description text,
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
  timestamp bigint DEFAULT (EXTRACT(epoch FROM now()))::bigint,
  created_at timestamp with time zone DEFAULT now(),
  creator_user_id bigint NOT NULL,
  CONSTRAINT courses_pkey PRIMARY KEY (id),
  CONSTRAINT fk_courses_creator_user FOREIGN KEY (creator_user_id) REFERENCES public.usuarios(id)
);
CREATE TABLE public.file_contexts (
  id bigint NOT NULL DEFAULT nextval('file_contexts_id_seq'::regclass),
  submission_id bigint,
  file_name text,
  file_type text,
  file_content text,
  extracted_text text,
  metadata text,
  content_summary text,
  created_at timestamp with time zone DEFAULT now(),
  CONSTRAINT file_contexts_pkey PRIMARY KEY (id),
  CONSTRAINT file_contexts_submission_id_fkey FOREIGN KEY (submission_id) REFERENCES public.task_submissions(id)
);
CREATE TABLE public.likes (
  usuario_id bigint NOT NULL,
  entity_type text NOT NULL,
  entity_id bigint NOT NULL,
  created_at timestamp with time zone DEFAULT now(),
  CONSTRAINT likes_pkey PRIMARY KEY (usuario_id, entity_type, entity_id),
  CONSTRAINT fk_likes_user FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id)
);
CREATE TABLE public.notifications (
  id bigint NOT NULL DEFAULT nextval('notifications_id_seq'::regclass),
  user_id bigint NOT NULL,
  type text NOT NULL,
  title text NOT NULL,
  message text NOT NULL,
  sender_username text,
  sender_avatar_url text,
  thumbnail_url text,
  related_id bigint,
  is_read boolean DEFAULT false,
  created_at timestamp with time zone DEFAULT now(),
  updated_at timestamp with time zone DEFAULT now(),
  CONSTRAINT notifications_pkey PRIMARY KEY (id),
  CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.usuarios(id)
);
CREATE TABLE public.personas (
  id bigint NOT NULL DEFAULT nextval('personas_id_seq'::regclass),
  identificacion text NOT NULL UNIQUE,
  nombres text NOT NULL,
  apellidos text NOT NULL,
  telefono text,
  direccion text,
  fecha_nacimiento date,
  created_at timestamp with time zone DEFAULT now(),
  CONSTRAINT personas_pkey PRIMARY KEY (id)
);
CREATE TABLE public.progreso_estudiante (
  curso_id bigint NOT NULL,
  tareas_completadas integer DEFAULT 0,
  tareas_totales integer DEFAULT 0,
  porcentaje_progreso real DEFAULT 0,
  calificacion_ponderada real,
  estado text DEFAULT 
CASE
    WHEN (COALESCE(calificacion_ponderada, (0)::real) >= (6)::double precision) THEN 'Ganado'::text
    ELSE 'Perdido'::text
END,
  ultima_calculada_en timestamp with time zone DEFAULT now(),
  certificado_emitido_en timestamp with time zone,
  creado_en timestamp with time zone DEFAULT now(),
  promedio real,
  usuario_estudiante bigint NOT NULL,
  certificado_url text,
  CONSTRAINT progreso_estudiante_pkey PRIMARY KEY (usuario_estudiante, curso_id),
  CONSTRAINT progreso_estudiante_curso_id_fkey FOREIGN KEY (curso_id) REFERENCES public.courses(id)
);
CREATE TABLE public.recursos (
  id bigint NOT NULL DEFAULT nextval('recursos_id_seq'::regclass),
  nombre text NOT NULL,
  icono text NOT NULL,
  orden integer NOT NULL,
  padre_id bigint,
  interfaz text,
  created_at timestamp with time zone DEFAULT now(),
  CONSTRAINT recursos_pkey PRIMARY KEY (id),
  CONSTRAINT recursos_padre_id_fkey FOREIGN KEY (padre_id) REFERENCES public.recursos(id)
);
CREATE TABLE public.reinforcement_question_history (
  id integer NOT NULL DEFAULT nextval('reinforcement_question_history_id_seq'::regclass),
  user_id integer NOT NULL,
  course_id integer NOT NULL,
  questions jsonb NOT NULL,
  created_at timestamp with time zone DEFAULT now(),
  topic_id bigint,
  task_id bigint,
  CONSTRAINT reinforcement_question_history_pkey PRIMARY KEY (id)
);
CREATE TABLE public.rol_recursos (
  rol_id bigint NOT NULL,
  recurso_id bigint NOT NULL,
  CONSTRAINT rol_recursos_pkey PRIMARY KEY (rol_id, recurso_id),
  CONSTRAINT rol_recursos_rol_id_fkey FOREIGN KEY (rol_id) REFERENCES public.roles(id),
  CONSTRAINT rol_recursos_recurso_id_fkey FOREIGN KEY (recurso_id) REFERENCES public.recursos(id)
);
CREATE TABLE public.roles (
  id bigint NOT NULL DEFAULT nextval('roles_id_seq'::regclass),
  nombre text NOT NULL UNIQUE,
  nivel real NOT NULL,
  default boolean DEFAULT false,
  created_at timestamp with time zone DEFAULT now(),
  CONSTRAINT roles_pkey PRIMARY KEY (id)
);
CREATE TABLE public.subscriptions (
  subscription_date bigint,
  created_at timestamp with time zone DEFAULT now(),
  subscriber_id bigint NOT NULL,
  creator_id bigint NOT NULL,
  CONSTRAINT subscriptions_pkey PRIMARY KEY (subscriber_id, creator_id),
  CONSTRAINT subscriptions_subscriber_id_fkey FOREIGN KEY (subscriber_id) REFERENCES public.usuarios(id),
  CONSTRAINT subscriptions_creator_id_fkey FOREIGN KEY (creator_id) REFERENCES public.usuarios(id)
);
CREATE TABLE public.task_submissions (
  id bigint NOT NULL DEFAULT nextval('task_submissions_id_seq'::regclass),
  task_id bigint,
  file_uri text,
  file_name text,
  submission_date bigint,
  grade real,
  feedback text,
  created_at timestamp with time zone DEFAULT now(),
  student_id integer,
  CONSTRAINT task_submissions_pkey PRIMARY KEY (id),
  CONSTRAINT task_submissions_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.tasks(id),
  CONSTRAINT fk_task_submissions_student_id FOREIGN KEY (student_id) REFERENCES public.usuarios(id)
);
CREATE TABLE public.tasks (
  id bigint NOT NULL DEFAULT nextval('tasks_id_seq'::regclass),
  topic_id bigint,
  title text NOT NULL,
  description text,
  due_date timestamp with time zone,
  created_at timestamp with time zone DEFAULT now(),
  CONSTRAINT tasks_pkey PRIMARY KEY (id),
  CONSTRAINT tasks_topic_id_fkey FOREIGN KEY (topic_id) REFERENCES public.topics(id)
);
CREATE TABLE public.topics (
  id bigint NOT NULL DEFAULT nextval('topics_id_seq'::regclass),
  course_id bigint,
  name text NOT NULL,
  description text,
  order_index integer DEFAULT 0,
  created_at timestamp with time zone DEFAULT now(),
  CONSTRAINT topics_pkey PRIMARY KEY (id),
  CONSTRAINT topics_course_id_fkey FOREIGN KEY (course_id) REFERENCES public.courses(id)
);
CREATE TABLE public.transactions (
  id bigint NOT NULL DEFAULT nextval('transactions_id_seq_custom'::regclass),
  user_id bigint NOT NULL,
  course_id bigint NOT NULL,
  amount numeric NOT NULL,
  currency text DEFAULT 'COP'::text,
  status text NOT NULL DEFAULT 'PENDING'::text,
  payment_method text DEFAULT 'PSE'::text,
  external_reference text,
  transaction_date timestamp with time zone DEFAULT now(),
  updated_at timestamp with time zone DEFAULT now(),
  encrypted_metadata text,
  client_ip text,
  user_agent text,
  provider_response jsonb,
  audit_log jsonb DEFAULT '[]'::jsonb,
  CONSTRAINT transactions_pkey PRIMARY KEY (id),
  CONSTRAINT fk_transactions_user FOREIGN KEY (user_id) REFERENCES public.usuarios(id),
  CONSTRAINT fk_transactions_course FOREIGN KEY (course_id) REFERENCES public.courses(id)
);
CREATE TABLE public.user_fcm_tokens (
  user_id bigint NOT NULL,
  token text NOT NULL,
  device_type text DEFAULT 'android'::text,
  last_updated timestamp with time zone DEFAULT now(),
  CONSTRAINT user_fcm_tokens_pkey PRIMARY KEY (user_id, token),
  CONSTRAINT user_fcm_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.usuarios(id)
);
CREATE TABLE public.usuarios (
  id bigint NOT NULL DEFAULT nextval('usuarios_id_seq'::regclass),
  username text NOT NULL UNIQUE,
  contrasena text NOT NULL,
  persona_id bigint,
  created_at timestamp with time zone DEFAULT now(),
  email text NOT NULL UNIQUE,
  avatar text,
  is_active boolean DEFAULT true,
  CONSTRAINT usuarios_pkey PRIMARY KEY (id),
  CONSTRAINT fk_usuarios_persona FOREIGN KEY (persona_id) REFERENCES public.personas(id),
  CONSTRAINT usuarios_persona_id_fkey FOREIGN KEY (persona_id) REFERENCES public.personas(id)
);
CREATE TABLE public.usuarios_roles (
  usuario_id bigint NOT NULL,
  rol_id integer NOT NULL,
  asignado_en timestamp with time zone DEFAULT now(),
  CONSTRAINT usuarios_roles_pkey PRIMARY KEY (usuario_id, rol_id),
  CONSTRAINT fk_usuarios_roles_usuario FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id),
  CONSTRAINT fk_usuarios_roles_rol FOREIGN KEY (rol_id) REFERENCES public.roles(id)
);
CREATE TABLE public.video_comments (
  id bigint NOT NULL DEFAULT nextval('video_comments_id_seq'::regclass),
  video_id bigint NOT NULL,
  usuario_id bigint NOT NULL,
  comment text NOT NULL,
  created_at timestamp with time zone NOT NULL DEFAULT now(),
  parent_id bigint,
  CONSTRAINT video_comments_pkey PRIMARY KEY (id),
  CONSTRAINT fk_video_comments_parent FOREIGN KEY (parent_id) REFERENCES public.video_comments(id),
  CONSTRAINT fk_video_comments_user FOREIGN KEY (usuario_id) REFERENCES public.usuarios(id),
  CONSTRAINT fk_video_comments_video FOREIGN KEY (video_id) REFERENCES public.videos(id)
);
CREATE TABLE public.videos (
  id bigint NOT NULL DEFAULT nextval('videos_id_seq'::regclass),
  description text,
  title text NOT NULL,
  video_uri_string text,
  local_file_path text,
  timestamp bigint,
  is_paid boolean DEFAULT false,
  thumbnail_uri text,
  price numeric,
  created_at timestamp with time zone DEFAULT now(),
  remote_id bigint,
  course_id bigint,
  CONSTRAINT videos_pkey PRIMARY KEY (id),
  CONSTRAINT fk_videos_creator_user FOREIGN KEY (remote_id) REFERENCES public.usuarios(id),
  CONSTRAINT videos_course_id_fkey FOREIGN KEY (course_id) REFERENCES public.courses(id)
);
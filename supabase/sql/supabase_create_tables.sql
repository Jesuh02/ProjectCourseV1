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

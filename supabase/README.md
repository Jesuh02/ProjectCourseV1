Cómo aplicar migraciones en Supabase

1) Usar el SQL Editor en el Dashboard
   - Abre Supabase Dashboard → SQL Editor
   - Pega el contenido de `supabase/migrations/0001_create_tables.sql` y ejecútalo

2) Usar supabase CLI (recomendado para CI)
   - Instala supabase CLI: https://supabase.com/docs/guides/cli
   - En el repositorio, guarda las migraciones en `supabase/migrations/`
   - En tu máquina con `supabase` CLI autenticado, ejecuta:

     supabase db remote set <YOUR_DB_CONNECTION_STRING>
     supabase db push --file supabase/migrations/0001_create_tables.sql

3) Seguridad
   - No expongas la `service_role` key en el cliente.
   - Usa RLS y políticas (Row Level Security) para proteger datos sensibles.

4) Verificar
   - En Dashboard → Database → Tables deberías ver las tablas creadas.
   - Ejecuta consultas en SQL Editor para validar.

Si quieres, puedo generar un script que use la REST Admin SQL (requiere service_role) para aplicar las migraciones automáticamente desde la máquina.

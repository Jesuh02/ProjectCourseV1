Cómo configurar Supabase y seguridad

1) No agregues las claves de servicio en el control de versiones. Usa `local.properties` (no commiteado) para las claves.

En `local.properties` en el directorio raíz del proyecto Android añade:

SUPABASE_URL=https://vxuksizvwrkctrvpciyp.supabase.co
SUPABASE_KEY=<PUBLIC_ANON_OR_SERVICE_KEY>
HOST_IP=192.168.1.16  # Opcional: la IP del equipo anfitrión (útil para emuladores)

- Para operaciones desde el cliente usa la clave `anon` (más restringida).
- Nunca incluyas `service_role` en la app cliente. Si necesitas usar `service_role` (p. ej. para crear datos con privilegios especiales), llama a un servidor con credenciales seguras y expone una API propia.

2) `app/build.gradle.kts` ya expone `BuildConfig.SUPABASE_URL` y `BuildConfig.SUPABASE_KEY` leyendo `local.properties`.

3) El código agregado `SupabaseClient.kt` realiza llamadas REST a la API de Supabase usando la clave provista.

4) Seguridad recomendada:
- Usa la `anon` key para inserciones simples y reglas RLS (Row Level Security) que permitan escritura sólo a usuarios autenticados.
- Usa funciones en un backend para operaciones sensibles y almacena `service_role` allí.
- Habilita RLS en Supabase y crea políticas para `personas` y `usuarios`.

5) SQL DDL:
- El archivo `supabase/sql/supabase_create_tables.sql` contiene la creación de tablas `personas` y `usuarios`.

6) Próximos pasos sugeridos:
- Revisar y ajustar las políticas RLS en Supabase.
- Implementar verificación de emails y manejo de contraseñas (actualmente la app envía contraseñas hasheadas con BCrypt; considera usar el auth provider de Supabase para manejo centralizado).

Notas sobre emuladores y networking:
- Si pruebas en el emulador Android estándar (Android Emulator), la IP `10.0.2.2` mappea a la máquina anfitrión. `app/build.gradle.kts` expone `BuildConfig.HOST_IP` leyendo `HOST_IP` de `local.properties` con valor por defecto `10.0.2.2`.
- Si usas un dispositivo físico o emulador distinto (p. ej. Genymotion), usa la IP del host en la red (ej. `192.168.1.16`).
- Si tu Supabase URL es pública (`https://vxuksizvwrkctrvpciyp.supabase.co`) normalmente no necesitas cambiar la IP. La opción `HOST_IP` es útil para servidores locales o recursos que corren en la máquina de desarrollo.

Project URL:
https://vxuksizvwrkctrvpciyp.supabase.co

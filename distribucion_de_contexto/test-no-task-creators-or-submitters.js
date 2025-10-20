import 'dotenv/config';
import { createClient } from '@supabase/supabase-js';

const supabaseUrl = process.env.SUPABASE_URL;
const supabaseKey = process.env.SUPABASE_SERVICE_KEY || process.env.SUPABASE_ANON_KEY;

if (!supabaseUrl || !supabaseKey) {
  console.error('Missing Supabase credentials in environment');
  process.exit(1);
}

const supabase = createClient(supabaseUrl, supabaseKey);

async function findUsersWithoutTasks() {
  try {
    const { data: users, error: uError } = await supabase
      .from('usuarios')
      .select('id, usuario, persona_id');

    if (uError) throw uError;

    const { data: personasMap, error: pError } = await supabase
      .from('personas')
      .select('id, nombres, apellidos, email');

    if (pError) throw pError;

    const personaById = new Map((personasMap || []).map(p => [p.id, p]));

    const tablesToCheck = [
      { name: 'tasks', columns: ['creator_username', 'creator_usuario_id', 'usuario_id'] },
      { name: 'task_submissions', columns: ['usuario_id', 'creator_username'] }
    ];

    const result = [];

    for (const u of users || []) {
      let hasRef = false;

      for (const t of tablesToCheck) {
        for (const col of t.columns) {
          try {
            const value = col.includes('_id') ? u.id : u.usuario;
            const { count } = await supabase
              .from(t.name)
              .select('id', { head: true, count: 'exact' })
              .eq(col, value);

            if (count > 0) {
              hasRef = true;
              break;
            }
          } catch (e) {
            // ignore missing columns/tables
          }
        }
        if (hasRef) break;
      }

      if (!hasRef) {
        const persona = personaById.get(u.persona_id) || {};
        result.push({ id: u.id, usuario: u.usuario, nombres: persona.nombres || null, apellidos: persona.apellidos || null, email: persona.email || null });
      }
    }

    console.log(JSON.stringify(result, null, 2));
    console.log(`\nTotal: ${result.length}/${(users || []).length}`);
    process.exit(0);
  } catch (error) {
    console.error('Error:', error);
    process.exit(1);
  }
}

findUsersWithoutTasks();

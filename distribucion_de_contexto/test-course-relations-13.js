import 'dotenv/config';
import { createClient } from '@supabase/supabase-js';

const supabaseUrl = process.env.SUPABASE_URL;
const supabaseKey = process.env.SUPABASE_SERVICE_KEY || process.env.SUPABASE_ANON_KEY;
if (!supabaseUrl || !supabaseKey) { console.error('Missing SUPABASE credentials'); process.exit(1); }
const supabase = createClient(supabaseUrl, supabaseKey);

async function main() {
  try {
    const { data: course, error: courseError } = await supabase.from('courses').select('*').eq('id', 13).single();
    if (courseError) {
      console.error('Error fetching course:', courseError);
    } else {
      console.log('Course:', course);
    }

    const tables = ['videos','content_items','topics','tasks','file_contexts','subscriptions','task_submissions','courses'];
    const relations = {};

    for (const t of tables) {
      try {
        // Check if course_id column exists by attempting a zero-row select
        const { error: colError } = await supabase.from(t).select('id', { head: true, count: 'exact' }).limit(0);
        // now try to count rows with course_id = 13 if column exists
        const { data, error, count } = await supabase.from(t).select('id', { head: true, count: 'exact' }).eq('course_id', 13);
        if (!error && count !== undefined && count > 0) {
          relations[t] = count;
        }
      } catch (e) {
        // ignore missing table/column
      }
    }

    console.log('\nRelations with course_id = 13:');
    console.log(JSON.stringify(relations, null, 2));
    process.exit(0);
  } catch (error) {
    console.error('Fatal error:', error);
    process.exit(1);
  }
}

main();

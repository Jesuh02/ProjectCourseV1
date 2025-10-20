import 'dotenv/config';
import { SupabaseService } from './src/infrastructure/database/SupabaseService.js';
import { MCPService } from './src/domain/services/MCPService.js';

async function testRawSQL() {
  console.log('Testing raw SQL execution...\n');
  
  const mcpService = new MCPService();
  
  const queries = [
    'SELECT * FROM courses WHERE id = 13',
    'SELECT id, title, creator_username FROM courses WHERE id = 13',
    'SELECT * FROM courses LIMIT 5'
  ];
  
  for (const query of queries) {
    console.log(`\n${'='.repeat(80)}`);
    console.log(`Query: ${query}`);
    console.log('='.repeat(80));
    
    try {
      const result = await mcpService.processQuery(query);
      console.log('\nResult:');
      console.log(JSON.stringify(result, null, 2));
    } catch (error) {
      console.error('\nError:', error.message);
    }
  }
}

testRawSQL()
  .then(() => {
    console.log('\n\n✅ Test completed');
    process.exit(0);
  })
  .catch((error) => {
    console.error('\n❌ Test failed:', error);
    process.exit(1);
  });

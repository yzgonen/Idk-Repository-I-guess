import 'dotenv/config';
import fs from 'node:fs';
import path from 'node:path';
import sharp from 'sharp';
import {Client,GatewayIntentBits,REST,Routes,SlashCommandBuilder,AttachmentBuilder,PermissionFlagsBits} from 'discord.js';

const {DISCORD_TOKEN:token,CLIENT_ID:clientId,GUILD_ID:guildId}=process.env;
if(!token||!clientId||!guildId){console.error('Set DISCORD_TOKEN, CLIENT_ID and GUILD_ID in .env');process.exit(1);}
const tiers=['S','A','B','C','D','F'];
const colors={S:'#ff7777',A:'#ffb879',B:'#ffe081',C:'#afe48b',D:'#80bef8',F:'#b89aee'};
const dataPath=path.join(process.cwd(),'tierlists.json');
let state={};
try{state=JSON.parse(fs.readFileSync(dataPath,'utf8'));}catch{}
const save=()=>fs.writeFileSync(dataPath,JSON.stringify(state,null,2));
const cmd=new SlashCommandBuilder().setName('tierlist').setDescription('Manage SpeedSMP Minecraft tiers')
.setDefaultMemberPermissions(PermissionFlagsBits.ManageGuild)
.addSubcommand(s=>s.setName('create').setDescription('Create a tier list here'))
.addSubcommand(s=>s.setName('add').setDescription('Add a Minecraft player').addStringOption(o=>o.setName('username').setDescription('Minecraft username').setRequired(true)).addStringOption(o=>o.setName('tier').setDescription('Starting tier').addChoices(...tiers.map(t=>({name:t,value:t})))))
.addSubcommand(s=>s.setName('move').setDescription('Move a player').addStringOption(o=>o.setName('username').setDescription('Minecraft username').setRequired(true)).addStringOption(o=>o.setName('tier').setDescription('Destination tier').setRequired(true).addChoices(...tiers.map(t=>({name:t,value:t})))))
.addSubcommand(s=>s.setName('remove').setDescription('Remove a player').addStringOption(o=>o.setName('username').setDescription('Minecraft username').setRequired(true)))
.addSubcommand(s=>s.setName('show').setDescription('Refresh the tier-list image'));
function escapeXml(v){return String(v).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;').replaceAll("'",'&apos;');}
async function head(name){
 try{const res=await fetch('https://minotar.net/helm/'+encodeURIComponent(name)+'/64.png',{signal:AbortSignal.timeout(8000)});if(!res.ok)throw Error('No skin');return sharp(Buffer.from(await res.arrayBuffer())).resize(64,64,{kernel:'nearest'}).png().toBuffer();}
 catch{return sharp({create:{width:64,height:64,channels:4,background:'#50576a'}}).png().toBuffer();}
}
async function render(players){
 const W=1100,H=740,labelW=100,cell=100,rowH=108;
 const svg=['<svg xmlns="http://www.w3.org/2000/svg" width="'+W+'" height="'+H+'"><rect width="100%" height="100%" fill="#10141f"/><text x="24" y="48" fill="white" font-family="Arial" font-size="30" font-weight="bold">SpeedSMP Tier List</text>'];
 const composite=[];
 for(let i=0;i<tiers.length;i++){
  const t=tiers[i],y=76+i*rowH;
  svg.push('<rect x="0" y="'+y+'" width="'+W+'" height="'+(rowH-2)+'" fill="#202634"/><rect x="0" y="'+y+'" width="'+labelW+'" height="'+(rowH-2)+'" fill="'+colors[t]+'"/><text x="50" y="'+(y+68)+'" text-anchor="middle" fill="#101010" font-family="Arial" font-size="46" font-weight="bold">'+t+'</text>');
  const arr=players.filter(p=>p.tier===t).slice(0,9);
  for(let j=0;j<arr.length;j++){
   const x=labelW+12+j*cell;
   composite.push({input:await head(arr[j].name),left:x+12,top:y+8});
   svg.push('<text x="'+(x+44)+'" y="'+(y+95)+'" text-anchor="middle" fill="white" font-family="Arial" font-size="12">'+escapeXml(arr[j].name.slice(0,14))+'</text>');
  }
 }
 svg.push('</svg>');
 return sharp(Buffer.from(svg.join(''))).composite(composite).png().toBuffer();
}
async function refresh(channel,record){
 const attachment=new AttachmentBuilder(await render(record.players),{name:'speedsmp-tierlist.png'});
 if(record.messageId){try{const msg=await channel.messages.fetch(record.messageId);await msg.edit({content:'**SpeedSMP Tier List** · Updated automatically',files:[attachment]});return;}catch{}}
 const msg=await channel.send({content:'**SpeedSMP Tier List** · Updated automatically',files:[attachment]});
 record.messageId=msg.id;save();
}
const client=new Client({intents:[GatewayIntentBits.Guilds]});
client.once('ready',()=>console.log('Online as '+client.user.tag));
client.on('interactionCreate',async interaction=>{
 if(!interaction.isChatInputCommand()||interaction.commandName!=='tierlist')return;
 await interaction.deferReply({ephemeral:true});
 try{
  const id=interaction.channelId,sub=interaction.options.getSubcommand();
  if(sub==='create'){state[id]={players:[],messageId:null};save();await refresh(interaction.channel,state[id]);await interaction.editReply('Tier list created. Use /tierlist add.');return;}
  const record=state[id];if(!record){await interaction.editReply('Run /tierlist create first.');return;}
  if(sub==='add'){
   const name=interaction.options.getString('username').trim();
   if(!/^[A-Za-z0-9_]{3,16}$/.test(name))throw Error('Minecraft usernames must be 3–16 characters.');
   if(record.players.some(p=>p.name.toLowerCase()===name.toLowerCase()))throw Error('Player already exists.');
   if(record.players.length>=54)throw Error('Maximum 54 players.');
   record.players.push({name,tier:interaction.options.getString('tier')||'F'});
  }
  if(sub==='move'){
   const name=interaction.options.getString('username');
   const player=record.players.find(p=>p.name.toLowerCase()===name.toLowerCase());
   if(!player)throw Error('Player not found.');
   player.tier=interaction.options.getString('tier');
  }
  if(sub==='remove'){
   const name=interaction.options.getString('username'),old=record.players.length;
   record.players=record.players.filter(p=>p.name.toLowerCase()!==name.toLowerCase());
   if(record.players.length===old)throw Error('Player not found.');
  }
  save();await refresh(interaction.channel,record);await interaction.editReply('Tier list updated.');
 }catch(e){console.error(e);await interaction.editReply('Error: '+e.message);}
});
await new REST({version:'10'}).setToken(token).put(Routes.applicationGuildCommands(clientId,guildId),{body:[cmd.toJSON()]});
await client.login(token);

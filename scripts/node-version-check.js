// Versiones mínimas de Node.js con los parches de seguridad vigentes (aviso oficial del 21/10/2026).
// Actualizar cuando Node.js publique nuevas versiones de seguridad.
const [M,m,p]=process.versions.node.split('.').map(Number);
const min={22:[23,4],24:[22,1],26:[12,1]};
const r=min[M];
if(!r||m<r[0]||(m===r[0]&&p<r[1])){console.error('Node.js '+process.versions.node+' no tiene los parches de seguridad vigentes. Usa 24.22.1+ (recomendado), 22.23.4+ o 26.12.1+');
process.exit(1)}

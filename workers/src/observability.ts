// Bounded route-template aggregates. Never retain request URLs, bodies or credentials.
const allowed = new Set([
  "/healthz", "/api/v1/bind", "/api/v1/push", "/api/v1/device/revoke", "/api/v1/capabilities",
  "/api/v1/messages", "/api/v1/messages/ack", "/api/v1/receipts/status", "/api/v1/realtime/config", "/api/v1/realtime/stream", "/api/v1/push-capabilities",
  "/api/v1/states", "/api/v1/states/meta", "/api/v1/topics",
  "/api/admin/overview", "/api/admin/metrics", "/api/admin/ready", "/api/admin/messages/clear",
  "/api/admin/binding", "/api/admin/revoke", "/api/admin/test", "/api/admin/devices/delete",
  "/api/admin/applications", "/api/admin/applications/revoke", "/api/admin/applications/delete",
  ...["list","detail","clear"].map(action=>`/api/admin/states/${action}`),
  ...["list","detail","create","update","delete"].map(action=>`/api/admin/topics/${action}`),
]);
type Route = {requests:number;server_errors:number;total:number;slowest_ms:number;under_100ms:number;under_1s:number};
export class RequestMetrics {
  private started=performance.now();
  private routes=new Map<string,Route>();
  private requests=0;
  private failures=0;
  observe(path:string,status:number,duration:number){
    const key=allowed.has(path)?path:"unmatched";
    const row=this.routes.get(key)??{requests:0,server_errors:0,total:0,slowest_ms:0,under_100ms:0,under_1s:0};
    this.requests++;row.requests++;
    if(status>=500){this.failures++;row.server_errors++;}
    row.total+=duration;row.slowest_ms=Math.max(row.slowest_ms,duration);
    if(duration<100)row.under_100ms++;else if(duration<1000)row.under_1s++;
    this.routes.set(key,row);
  }
  snapshot(){return {uptime_seconds:Math.max(0,performance.now()-this.started)/1000,requests:this.requests,server_errors:this.failures,
    routes:Object.fromEntries([...this.routes].map(([key,row])=>[key,{
      requests:row.requests,server_errors:row.server_errors,mean_ms:row.requests?row.total/row.requests:0,
      slowest_ms:row.slowest_ms,under_100ms:row.under_100ms,under_1s:row.under_1s,
    }]))};}
}

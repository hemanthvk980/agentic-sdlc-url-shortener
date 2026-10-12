import { Routes } from '@angular/router';
import { ShortenComponent } from './shorten.component';
import { RunsComponent } from './runs.component';

export const routes: Routes = [
  { path: '', component: ShortenComponent, title: 'Shorten a link' },
  { path: 'runs', component: RunsComponent, title: 'Agent runs' },
  { path: '**', redirectTo: '' },
];
